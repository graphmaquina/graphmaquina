package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.ARCHIVE;
import static io.github.graphmaquina.fixtures.DocTransition.DISCARD;
import static io.github.graphmaquina.fixtures.DocTransition.PUBLISH;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.error.CallFailedException;
import io.github.graphmaquina.error.CommitFailedException;
import io.github.graphmaquina.error.ReentrantTransitionException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.error.UnhandledFailureException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Diagnostics whose text has to be true where it is produced: the reentrancy message
 * for a call from a guard, the pre-check message when a concurrent transition moved the state, and the failure
 * reason of an anonymous exception without a message.
 */
class DiagnosticsTest {

    /** DRAFT -SUBMIT-> REVIEW, shaped by {@code submit}; DRAFT -DISCARD-> ARCHIVED is the route a failure can take. */
    private static GraphDefinition<Doc, DocStatus, DocTransition> docs(
            UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>> submit) {
        return submit.apply(StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                        .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                        .transition(SUBMIT, DRAFT, REVIEW))
                .endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
    }

    private static StateMachine<Doc, DocStatus, DocTransition> standalone(GraphDefinition<Doc, DocStatus, DocTransition> graph) {
        return StateMachines.from(graph).state(Doc::status, Doc::setStatus).build();
    }

    /**
     * A guard has no cascade to turn to: the message names the callbacks that cannot start a transition, those of a
     * transition and those of a watch, and keeps the cascade advice for actions.
     */
    @Test
    void aReentrantCallFromAGuardIsNotToldToCascade() {
        Doc other = new Doc("2", DRAFT);
        AtomicReference<StateMachine<Doc, DocStatus, DocTransition>> self = new AtomicReference<>();
        StateMachine<Doc, DocStatus, DocTransition> machine = standalone(docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .guard("Peeks", (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                            self.get().execute(other, DISCARD);
                            return GuardResult.pass();
                        })));
        self.set(machine);
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                () -> machine.execute(new Doc("1", DRAFT), SUBMIT));
        ReentrantTransitionException reentrant = assertInstanceOf(ReentrantTransitionException.class, error.getCause());
        assertEquals("execute/reevaluate on Doc/DocStatus from within a transition, a watch evaluation or an observer"
                + " callback already in progress on this thread: a guard, an action, a watch's peersOf, ownerOf or"
                + " condition, a state accessor, an identity function or an observer cannot start another transition;"
                + " an action moves its peers with ctx.cascade(...)", reentrant.getMessage());
        assertEquals(DRAFT, other.status());
    }

    /**
     * A state getter, an identity function and a setter are callbacks of the call too, so one that starts a
     * transition is refused, and the refusal arrives as the cause of the call's failure: an abort in RESOLVE when the
     * identity or the getter reads the entity, a commit failure with nothing written when the getter does it during
     * the commit's check, and one that wrote some states when the setter does.
     */
    @Test
    void aReentrantCallFromAnAccessorArrivesAsTheCauseOfTheFailureOfTheCall() {
        Doc other = new Doc("2", DRAFT);
        AtomicReference<StateMachine<Doc, DocStatus, DocTransition>> self = new AtomicReference<>();
        Runnable startsATransition = () -> self.get().execute(other, DISCARD);
        AtomicInteger reads = new AtomicInteger();
        List<String> ends = new ArrayList<>();
        for (String accessor : List.of("identity", "getter", "check", "setter")) {
            reads.set(0);
            StateMachine<Doc, DocStatus, DocTransition> machine = StateMachines.from(docs(
                            UnaryOperator.<TransitionBuilder<Doc, DocStatus, DocTransition>>identity()))
                    .state((Doc doc) -> {
                        int read = reads.incrementAndGet();
                        if (accessor.equals("getter") && read == 1 || accessor.equals("check") && read == 2) {
                            startsATransition.run();
                        }
                        return doc.status();
                    }, (Doc doc, DocStatus status) -> {
                        if (accessor.equals("setter")) {
                            startsATransition.run();
                        }
                        doc.setStatus(status);
                    })
                    .identity((Doc doc) -> {
                        if (accessor.equals("identity")) {
                            startsATransition.run();
                        }
                        return doc.id();
                    }).build();
            self.set(machine);
            CallFailedException failure = assertThrows(CallFailedException.class,
                    () -> machine.execute(new Doc("1", DRAFT), SUBMIT));
            ends.add(accessor + ": " + failure.getClass().getSimpleName() + " " + failure.phase() + " "
                    + failure.statesWritten() + " caused by " + failure.getCause().getClass().getSimpleName());
        }
        assertEquals(List.of(
                "identity: TransitionAbortedException RESOLVE NONE caused by ReentrantTransitionException",
                "getter: TransitionAbortedException RESOLVE NONE caused by ReentrantTransitionException",
                "check: CommitFailedException COMMIT NONE caused by ReentrantTransitionException",
                "setter: CommitFailedException COMMIT PARTIAL caused by ReentrantTransitionException"), ends);
        assertEquals(DRAFT, other.status());
    }

    /**
     * Nothing serializes two roots on one entity: while one waits in its action, another commits, and the first
     * one's pre-check finds the state moved. No action called the setter, so the message has to name a concurrent
     * transition among the causes, not blame the setter alone.
     */
    @Test
    void thePreCheckNamesAConcurrentTransitionAsWellAsTheSetter() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        StateMachine<Doc, DocStatus, DocTransition> machine = standalone(docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            inside.countDown();
                            try {
                                release.await(10, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return ActionOutcome.ok();
                        })));
        Doc doc = new Doc("1", DRAFT);
        try (ExecutorService other = Executors.newSingleThreadExecutor()) {
            Future<Outcome<Doc, DocStatus, DocTransition>> first = other.submit(() -> machine.execute(doc, SUBMIT));
            inside.await(10, TimeUnit.SECONDS);
            assertInstanceOf(Outcome.Applied.class, machine.execute(doc, DISCARD));
            release.countDown();
            ExecutionException wrapped = assertThrows(ExecutionException.class, first::get);
            CommitFailedException error = assertInstanceOf(CommitFailedException.class, wrapped.getCause());
            assertEquals("phase 2 failed: the state of Doc/DocStatus#@" + Integer.toHexString(System.identityHashCode(doc))
                    + " changed during the transaction: expected DRAFT but is ARCHIVED; an action called the setter, or a"
                    + " concurrent transition on the same entity wrote it first; applied 0, pending 1", error.getMessage());
            assertEquals(ARCHIVED, doc.status());
        }
    }

    /** A failure reason is never blank, even for an anonymous exception class without a message. */
    @Test
    void aFailureReasonNamesAnAnonymousExceptionWithoutAMessage() {
        IllegalStateException anonymous = new IllegalStateException() { };
        StateMachine<Doc, DocStatus, DocTransition> routed = standalone(docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            throw anonymous;
                        })
                        .failsOn(IllegalStateException.class).onFailure(DISCARD)));
        Outcome.Failed<?, ?, ?> failed = assertInstanceOf(Outcome.Failed.class, routed.execute(new Doc("1", DRAFT), SUBMIT));
        assertEquals(anonymous.getClass().getName(), failed.reason());

        StateMachine<Doc, DocStatus, DocTransition> unrouted = standalone(docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            throw anonymous;
                        })
                        .failsOn(IllegalStateException.class)));
        UnhandledFailureException unhandled = assertThrows(UnhandledFailureException.class,
                () -> unrouted.execute(new Doc("1", DRAFT), SUBMIT));
        assertEquals(anonymous.getClass().getName(), unhandled.reason());
    }
}
