package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.DocGraphs.docs;
import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocTransition.DISCARD;
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
import io.github.graphmaquina.dsl.TransitionBuilder;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Diagnostics whose text has to be true where it is produced: the reentrancy message for each refusal, the
 * pre-check message when a concurrent transition moved the state, and the failure reason of an anonymous exception
 * without a message.
 */
class DiagnosticsTest {

    private static StateMachine<Doc, DocStatus, DocTransition> standalone(
            GraphDefinition<Doc, DocStatus, DocTransition> graph) {
        return StateMachines.from(graph).state(Doc::status, Doc::setStatus).build();
    }

    /**
     * A guard has no cascade to turn to: the message names the callbacks that cannot start a transition, those of a
     * transition and those of a watch, says that a synchronous guard can check an entity no enclosing call or check
     * holds, and keeps the cascade advice for actions.
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
        assertEquals("execute/reevaluate/advance/choose/eligibility on Doc/DocStatus from within a transition, an"
                + " eligibility check, a watch evaluation or an observer callback already in progress on this thread: a"
                + " guard, an action, a sub-machine's childOf or parentOf, a watch's peersOf, ownerOf or condition, a"
                + " state accessor, an identity function or an observer cannot start another transition or eligibility"
                + " check, except that a synchronous guard can check an entity that no enclosing call or check holds;"
                + " an action moves its peers with ctx.cascade(...)",
                reentrant.getMessage());
        assertEquals(DRAFT, other.status());
    }

    /** A guard's check of its own entity is told that the entity is held, and which entities are. */
    @Test
    void aGuardsCheckOfAHeldEntityIsToldThatItIsHeld() {
        Doc root = new Doc("1", DRAFT);
        List<String> messages = new ArrayList<>();
        AtomicReference<StateMachine<Doc, DocStatus, DocTransition>> self = new AtomicReference<>();
        self.set(standalone(docs((TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                .guard("Checks", (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                    messages.add(assertThrows(ReentrantTransitionException.class,
                            () -> self.get().eligibility(root, DISCARD)).getMessage());
                    return GuardResult.pass();
                }))));
        assertInstanceOf(Outcome.Applied.class, self.get().execute(root, SUBMIT));
        assertEquals(List.of("eligibility on Doc/DocStatus from a synchronous guard, of an entity that an enclosing"
                + " call or check holds: the root entity of an enclosing call or check, the entity of an enclosing"
                + " cascade or exit step, or an entity with a pending hop or sub-machine start in an enclosing call; a"
                + " guard can check only an entity that no enclosing call or check holds"), messages);
    }

    /** The guard of a check nested in the root's guard reads the root through its view, and is told why not. */
    @Test
    void aNestedChecksReadOfAHeldEntityIsToldThatItIsHeld() {
        Doc root = new Doc("1", DRAFT);
        List<String> messages = new ArrayList<>();
        AtomicReference<StateMachine<Doc, DocStatus, DocTransition>> self = new AtomicReference<>();
        self.set(standalone(docs((TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                .guard("Reads", (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                    if (ctx.entity() == root) {
                        self.get().eligibility(new Doc("2", DRAFT), SUBMIT);
                    } else {
                        messages.add(assertThrows(ReentrantTransitionException.class,
                                () -> ctx.peer(DOCS).stateOf(root)).getMessage());
                    }
                    return GuardResult.pass();
                }))));
        assertInstanceOf(Outcome.Applied.class, self.get().execute(root, SUBMIT));
        assertEquals(List.of("a read of an entity of Doc/DocStatus that an enclosing call or check holds, through a"
                + " context's peer view or a binding lookup, in the scope of a check that a guard started: a nested"
                + " check cannot read an entity that an enclosing call or check has in flight, or whose hop or start"
                + " is pending there"), messages);
    }

    /**
     * The root's action keeps its context and cascades to a step, whose guard starts a check; that check's guard
     * cascades through the kept context, and is told that a nested check cannot move an entity of its call.
     */
    @Test
    void aNestedChecksCascadeThroughAKeptContextIsToldWhyNot() {
        Doc step = new Doc("2", DRAFT);
        Doc other = new Doc("3", DRAFT);
        List<String> messages = new ArrayList<>();
        AtomicReference<TransitionContext<Doc, DocStatus, DocTransition>> kept = new AtomicReference<>();
        AtomicReference<StateMachine<Doc, DocStatus, DocTransition>> self = new AtomicReference<>();
        self.set(standalone(docs((TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                .guard("Moves", (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                    if (ctx.entity() == step) {
                        self.get().eligibility(other, SUBMIT);
                    } else if (ctx.entity() == other) {
                        messages.add(assertThrows(ReentrantTransitionException.class,
                                () -> kept.get().cascade(DOCS, new Doc("4", DRAFT), SUBMIT)).getMessage());
                    }
                    return GuardResult.pass();
                }), (TransitionBuilder<Doc, DocStatus, DocTransition> discard) -> discard
                .action("keeps", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                    kept.set(ctx);
                    ctx.cascade(DOCS, step, SUBMIT);
                    return ActionOutcome.ok();
                }))));
        assertInstanceOf(Outcome.Applied.class, self.get().execute(new Doc("1", DRAFT), DISCARD));
        assertEquals(List.of("a cascade through a retained action context of Doc/DocStatus, in the scope of a check"
                + " that a guard started: a nested check cannot move an entity of the call it is nested in"), messages);
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
            assertEquals("phase 2 failed: the state of Doc/DocStatus#@"
                    + Integer.toHexString(System.identityHashCode(doc))
                    + " changed during the transaction: expected DRAFT but is ARCHIVED; an action called the setter,"
                    + " or a concurrent transition on the same entity wrote it first; applied 0, pending 1",
                    error.getMessage());
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
        Outcome.Failed<?, ?, ?> failed = assertInstanceOf(Outcome.Failed.class,
                routed.execute(new Doc("1", DRAFT), SUBMIT));
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
