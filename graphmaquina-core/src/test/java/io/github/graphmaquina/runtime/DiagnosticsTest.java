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
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.error.AfterCommitException;
import io.github.graphmaquina.error.CommitFailedException;
import io.github.graphmaquina.error.ReentrantTransitionException;
import io.github.graphmaquina.error.UnhandledFailureException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/** What the runtime's diagnostics say must hold for every situation that produces them. */
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
     * A listener that fails after a call that wrote nothing must not claim that states were written, and one that
     * fails after a call that wrote states must say so: Rejected and Ignored wrote nothing, Applied and Failed did.
     */
    @Test
    void aListenerFailureAfterACallThatWroteNothingSaysSo() {
        IllegalStateException audit = new IllegalStateException("audit down");
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(docs((TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                                .action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) ->
                                        ActionOutcome.failed("no content"))
                                .onFailure(DISCARD)))
                        .state(Doc::status, Doc::setStatus).identity(Doc::id))
                .listener((MachineKey<?, ?, ?> key, Outcome<?, ?, ?> outcome) -> {
                    throw audit;
                })
                .build()) {
            StateMachine<Doc, DocStatus, DocTransition> docs = registry.of(DOCS);
            Doc doc = new Doc("1", REVIEW);
            AfterCommitException rejected = assertThrows(AfterCommitException.class, () -> docs.execute(doc, SUBMIT));
            assertEquals("the call on Doc/DocStatus REVIEW → REVIEW wrote no state, and the post-commit step 'listener'"
                    + " failed", rejected.getMessage());
            AfterCommitException ignored = assertThrows(AfterCommitException.class, () -> docs.reevaluate(doc));
            assertEquals(rejected.getMessage(), ignored.getMessage());
            AfterCommitException applied = assertThrows(AfterCommitException.class, () -> docs.execute(doc, PUBLISH));
            assertEquals("the states of Doc/DocStatus REVIEW → PUBLISHED are already written, but the post-commit step"
                    + " 'listener' failed", applied.getMessage());
            assertEquals(PUBLISHED, doc.status());
            Doc draft = new Doc("2", DRAFT);
            AfterCommitException failed = assertThrows(AfterCommitException.class, () -> docs.execute(draft, SUBMIT));
            assertEquals("the states of Doc/DocStatus DRAFT → ARCHIVED are already written, but the post-commit step"
                    + " 'listener' failed", failed.getMessage());
            assertEquals(ARCHIVED, draft.status());
        }
    }

    /**
     * A guard has no cascade to turn to: the message names the callbacks that run inside a transition, and keeps the
     * cascade advice for actions.
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
        ReentrantTransitionException error = assertThrows(ReentrantTransitionException.class,
                () -> machine.execute(new Doc("1", DRAFT), SUBMIT));
        assertEquals("execute/reevaluate on Doc/DocStatus from within a transition already in progress on this thread:"
                + " a guard, an action, a state accessor or an identity function cannot start another transition; an"
                + " action moves its peers with ctx.cascade(...)", error.getMessage());
        assertEquals(DRAFT, other.status());
    }

    /**
     * Nothing serializes two roots on one entity: while one waits in its action, another commits, and the first
     * one's pre-check finds the state moved. No action called the setter, and the message must not say one did.
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
