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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.CallFailure;
import io.github.graphmaquina.api.CascadePolicy;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineCall;
import io.github.graphmaquina.api.MachineObserver;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.error.PoisonedUnitOfWorkException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.fixtures.Cell;
import io.github.graphmaquina.fixtures.CellChains;
import io.github.graphmaquina.fixtures.CellStatus;
import io.github.graphmaquina.fixtures.CellTransition;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import io.github.graphmaquina.fixtures.GatewayException;
import io.github.graphmaquina.fixtures.Probe;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A VirtualMachineError that leaves a cascade, a StackOverflowError, an OutOfMemoryError or an InternalError, reaches
 * the action at once and as itself, since recording it would need the stack or the memory it ran out of. It poisons
 * the call all the same: once the action goes on, the engine records, on that step's own stack, an abort of the step
 * that carries no cause, before it reads or stores any other poison, so an action that catches the Error cannot make
 * the call commit, a later peer read or cascade of that action is refused with that abort as the original failure,
 * COLLECT does not collect it, and an earlier poison stays first. The last test runs real overflows on a thread of a
 * small stack, where even recording an ordinary failure can overflow, and checks that nothing commits.
 */
class CascadeVirtualMachineErrorTest {

    private static final String LOST = "the action caught an Error that ended one of its cascades: the call cannot"
            + " commit";

    final Probe<Doc, DocStatus, DocTransition> submit = new Probe<>();
    final Probe<Doc, DocStatus, DocTransition> publish = new Probe<>();
    final Probe<Doc, DocStatus, DocTransition> archive = new Probe<>();
    final Probe<Doc, DocStatus, DocTransition> discard = new Probe<>();
    final List<String> failures = new ArrayList<>();
    final AtomicReference<Throwable> observed = new AtomicReference<>();

    /**
     * SUBMIT runs {@code submit} from DRAFT; PUBLISH runs {@code publish} from REVIEW and ARCHIVE runs {@code archive}
     * from PUBLISHED, which the tests cascade into. DISCARD is SUBMIT's failure route for a GatewayException, and runs
     * {@code discard}. An observer records the phase and states written of every failure, and the failure itself.
     */
    private StateMachine<Doc, DocStatus, DocTransition> machine() {
        GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                .transition(SUBMIT, DRAFT, REVIEW).action("Submit", submit)
                    .failsOn(GatewayException.class).onFailure(DISCARD).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).action("Publish", publish).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).action("Archive", archive).endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED).action("Discard", discard).endTransition()
                .build();
        return MachineRegistry.builder()
                .add(StateMachines.from(graph).state(Doc::status, Doc::setStatus).identity(Doc::id))
                .observer(new MachineObserver() {
                    @Override
                    public void onFailed(MachineCall call, CallFailure failure, Duration elapsed) {
                        failures.add(failure.phase() + " " + failure.statesWritten());
                        observed.set(failure.error());
                    }
                })
                .build()
                .of(DOCS);
    }

    private static List<Error> virtualMachineErrors() {
        return List.of(new StackOverflowError(), new OutOfMemoryError("heap"), new InternalError("jvm"));
    }

    /** {@code key FROM -ID-> TO} of the step an abort names, then its cause, then whether its message is LOST's. */
    private static String described(TransitionAbortedException abort) {
        return abort.key() + " " + abort.from().orElseThrow().name() + " -" + abort.transition().orElseThrow().name()
                + "-> " + abort.to().orElseThrow().name() + " of " + abort.entity() + ", cause " + abort.getCause()
                + (abort.getMessage().contains(LOST) ? ", lost cascade" : ", " + abort.getMessage());
    }

    /**
     * The peer's action throws the Error; the root's action catches it from its cascade and returns ok. The action
     * received the Error itself, the call aborts on the root's step, nothing is committed, and the observers hear of
     * that abort in ACTION.
     */
    @Test
    void anActionThatCatchesAVirtualMachineErrorOfItsCascadeAbortsOnItsOwnStep() {
        List<String> ends = new ArrayList<>();
        for (Error fatal : virtualMachineErrors()) {
            publish.behaviour = (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                throw fatal;
            };
            AtomicReference<Throwable> caught = new AtomicReference<>();
            submit.behaviour = (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                try {
                    ctx.cascade(DOCS, new Doc("peer", REVIEW), PUBLISH);
                } catch (Error swallowed) {
                    caught.set(swallowed);
                }
                return ActionOutcome.ok();
            };
            failures.clear();
            Doc doc = new Doc("root", DRAFT);
            TransitionAbortedException abort =
                    assertThrows(TransitionAbortedException.class, () -> machine().execute(doc, SUBMIT));
            assertSame(fatal, caught.get());
            assertSame(abort, observed.get());
            ends.add(fatal.getClass().getSimpleName() + ": " + described(abort) + ", " + doc.status() + ", "
                    + failures);
        }
        assertEquals(List.of(
                "StackOverflowError: Doc/DocStatus DRAFT -SUBMIT-> REVIEW of Doc#root, cause null, lost cascade, DRAFT,"
                        + " [ACTION NONE]",
                "OutOfMemoryError: Doc/DocStatus DRAFT -SUBMIT-> REVIEW of Doc#root, cause null, lost cascade, DRAFT,"
                        + " [ACTION NONE]",
                "InternalError: Doc/DocStatus DRAFT -SUBMIT-> REVIEW of Doc#root, cause null, lost cascade, DRAFT,"
                        + " [ACTION NONE]"), ends);
    }

    /** An action that lets the Error through ends the call in that very Error, which the observers hear of. */
    @Test
    void aVirtualMachineErrorThatTheActionLetsThroughEndsTheCallAsItself() {
        StackOverflowError overflow = new StackOverflowError();
        publish.behaviour = (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
            throw overflow;
        };
        submit.behaviour = (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
            ctx.cascade(DOCS, new Doc("peer", REVIEW), PUBLISH);
            return ActionOutcome.ok();
        };
        Doc doc = new Doc("root", DRAFT);
        StackOverflowError thrown = assertThrows(StackOverflowError.class, () -> machine().execute(doc, SUBMIT));
        assertSame(overflow, thrown);
        assertSame(overflow, observed.get());
        assertEquals(List.of("ACTION NONE"), failures);
        assertEquals(DRAFT, doc.status());
    }

    /**
     * Once the action caught the Error, a peer read and a cascade of that action are refused, each with the abort of
     * its step as the original failure, and the second cascade runs nothing.
     */
    @Test
    void aPeerReadOrACascadeAfterTheCaughtErrorIsRefusedWithTheAbortOfTheStep() {
        publish.behaviour = (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
            if (ctx.entity().id().equals("first")) {
                throw new OutOfMemoryError("heap");
            }
            return ActionOutcome.ok();
        };
        List<PoisonedUnitOfWorkException> refused = new ArrayList<>();
        Doc second = new Doc("second", REVIEW);
        submit.behaviour = (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
            try {
                ctx.cascade(DOCS, new Doc("first", REVIEW), PUBLISH);
            } catch (OutOfMemoryError swallowed) {
                // The action goes on.
            }
            try {
                ctx.peer(DOCS);
            } catch (PoisonedUnitOfWorkException poisoned) {
                refused.add(poisoned);
            }
            try {
                ctx.cascade(DOCS, second, PUBLISH);
            } catch (PoisonedUnitOfWorkException poisoned) {
                refused.add(poisoned);
            }
            return ActionOutcome.ok();
        };
        TransitionAbortedException abort =
                assertThrows(TransitionAbortedException.class, () -> machine().execute(new Doc("root", DRAFT), SUBMIT));
        assertEquals("Doc/DocStatus DRAFT -SUBMIT-> REVIEW of Doc#root, cause null, lost cascade", described(abort));
        assertEquals(2, refused.size());
        assertSame(abort, refused.get(0).original());
        assertSame(abort, refused.get(1).original());
        assertEquals(List.of("Doc#first:REVIEW-PUBLISH>PUBLISHED@1"), publish.calls);
        assertEquals(REVIEW, second.status());
    }

    /** A recovery action that catches the Error of its cascade aborts the call on the recovery's step. */
    @Test
    void aRecoveryActionThatCatchesAVirtualMachineErrorOfItsCascadeAbortsOnItsStep() {
        submit.behaviour = (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
            throw new GatewayException("down");
        };
        publish.behaviour = (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
            throw new StackOverflowError();
        };
        discard.behaviour = (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
            try {
                ctx.cascade(DOCS, new Doc("peer", REVIEW), PUBLISH);
            } catch (StackOverflowError swallowed) {
                // The recovery goes on.
            }
            return ActionOutcome.ok();
        };
        Doc doc = new Doc("root", DRAFT);
        TransitionAbortedException abort =
                assertThrows(TransitionAbortedException.class, () -> machine().execute(doc, SUBMIT));
        assertEquals("Doc/DocStatus DRAFT -DISCARD-> ARCHIVED of Doc#root, cause null, lost cascade",
                described(abort));
        assertEquals(DRAFT, doc.status());
    }

    /**
     * Whatever the policy, cascadeAll lets the Error of an element through at once, so no later element runs; the
     * action that catches it aborts on its own step, and none of the elements commits.
     */
    @Test
    void cascadeAllLetsAVirtualMachineErrorThroughAtOnceWhateverItsPolicy() {
        StackOverflowError overflow = new StackOverflowError();
        publish.behaviour = (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
            if (ctx.entity().id().equals("p2")) {
                throw overflow;
            }
            return ActionOutcome.ok();
        };
        List<String> ends = new ArrayList<>();
        for (CascadePolicy policy : CascadePolicy.values()) {
            publish.calls.clear();
            List<Doc> peers = List.of(new Doc("p1", REVIEW), new Doc("p2", REVIEW), new Doc("p3", REVIEW));
            AtomicReference<Throwable> caught = new AtomicReference<>();
            submit.behaviour = (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                try {
                    ctx.cascadeAll(DOCS, peers, PUBLISH, policy);
                } catch (Error swallowed) {
                    caught.set(swallowed);
                }
                return ActionOutcome.ok();
            };
            TransitionAbortedException abort = assertThrows(TransitionAbortedException.class,
                    () -> machine().execute(new Doc("root", DRAFT), SUBMIT));
            assertSame(overflow, caught.get());
            ends.add(policy + ": " + described(abort) + ", ran " + publish.calls.size() + ", "
                    + peers.stream().map(Doc::status).toList());
        }
        assertEquals(List.of(
                "FAIL_FAST: Doc/DocStatus DRAFT -SUBMIT-> REVIEW of Doc#root, cause null, lost cascade, ran 2,"
                        + " [REVIEW, REVIEW, REVIEW]",
                "COLLECT: Doc/DocStatus DRAFT -SUBMIT-> REVIEW of Doc#root, cause null, lost cascade, ran 2,"
                        + " [REVIEW, REVIEW, REVIEW]"), ends);
    }

    /**
     * Under COLLECT, an element whose action caught the Error of its own cascade ends in the abort of its step, which
     * is no failure that COLLECT collects: the cascade stops there, and the root's action receives that abort.
     */
    @Test
    void anElementWhoseActionCaughtAVirtualMachineErrorStopsTheCollect() {
        archive.behaviour = (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
            throw new StackOverflowError();
        };
        publish.behaviour = (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
            if (ctx.entity().id().equals("p2")) {
                try {
                    ctx.cascade(DOCS, new Doc("q", PUBLISHED), ARCHIVE);
                } catch (StackOverflowError swallowed) {
                    // The element goes on.
                }
            }
            return ActionOutcome.ok();
        };
        List<Doc> peers = List.of(new Doc("p1", REVIEW), new Doc("p2", REVIEW), new Doc("p3", REVIEW));
        AtomicReference<Throwable> caught = new AtomicReference<>();
        submit.behaviour = (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
            try {
                ctx.cascadeAll(DOCS, peers, PUBLISH, CascadePolicy.COLLECT);
            } catch (RuntimeException failure) {
                caught.set(failure);
                throw failure;
            }
            return ActionOutcome.ok();
        };
        TransitionAbortedException abort = assertThrows(TransitionAbortedException.class,
                () -> machine().execute(new Doc("root", DRAFT), SUBMIT));
        assertSame(abort, caught.get());
        assertEquals("Doc/DocStatus REVIEW -PUBLISH-> PUBLISHED of Doc#p2, cause null, lost cascade",
                described(abort));
        assertEquals(List.of("Doc#p1:REVIEW-PUBLISH>PUBLISHED@1", "Doc#p2:REVIEW-PUBLISH>PUBLISHED@1"),
                publish.calls);
        assertEquals(List.of(REVIEW, REVIEW, REVIEW), peers.stream().map(Doc::status).toList());
    }

    /**
     * A nested action that catches the Error of its cascade and then throws another failure ends the call in the
     * abort of its step that the lost Error left, which the root's action lets through.
     */
    @Test
    void anActionThatCatchesTheErrorAndThrowsAnotherFailureEndsInTheAbortOfItsStep() {
        archive.behaviour = (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
            throw new StackOverflowError();
        };
        publish.behaviour = (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
            try {
                ctx.cascade(DOCS, new Doc("q", PUBLISHED), ARCHIVE);
            } catch (StackOverflowError swallowed) {
                throw new IllegalStateException("went on");
            }
            return ActionOutcome.ok();
        };
        submit.behaviour = (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
            ctx.cascade(DOCS, new Doc("peer", REVIEW), PUBLISH);
            return ActionOutcome.ok();
        };
        Doc doc = new Doc("root", DRAFT);
        TransitionAbortedException abort =
                assertThrows(TransitionAbortedException.class, () -> machine().execute(doc, SUBMIT));
        assertEquals("Doc/DocStatus REVIEW -PUBLISH-> PUBLISHED of Doc#peer, cause null, lost cascade",
                described(abort));
        assertEquals(DRAFT, doc.status());
    }

    /**
     * A poison stored before the Error stays the poison: the nested action caught the abort of its own cascade, then
     * threw the Error, which the root's action caught; the call ends in that first abort.
     */
    @Test
    void anEarlierPoisonStaysFirst() {
        archive.behaviour = (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
            throw new IllegalStateException("archive failed");
        };
        AtomicReference<Throwable> first = new AtomicReference<>();
        publish.behaviour = (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
            try {
                ctx.cascade(DOCS, new Doc("q", PUBLISHED), ARCHIVE);
            } catch (TransitionAbortedException aborted) {
                first.set(aborted);
            }
            throw new StackOverflowError();
        };
        submit.behaviour = (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
            try {
                ctx.cascade(DOCS, new Doc("peer", REVIEW), PUBLISH);
            } catch (StackOverflowError swallowed) {
                // The root goes on.
            }
            return ActionOutcome.ok();
        };
        TransitionAbortedException abort = assertThrows(TransitionAbortedException.class,
                () -> machine().execute(new Doc("root", DRAFT), SUBMIT));
        assertSame(first.get(), abort);
        assertTrue(abort.getCause() instanceof IllegalStateException, String.valueOf(abort.getCause()));
    }

    /**
     * Real overflows: a chain of cells far deeper than a thread of 256 KiB holds, each of whose actions catches the
     * Error of its cascade and returns ok, runs under paddings that move where the stack runs out, into the engine's
     * own recording of a failure too. A cascade that overflows before it accepted its arguments, before it reads the
     * next cell's identity, poisons nothing, as a refused argument does, and the root may then commit. Once a cascade
     * read the next cell's identity, the root never returns, and no cell commits.
     */
    @Test
    void anOverflowThatAnActionCatchesOnceItsCascadeStartedNeverLetsTheRootCommit() throws InterruptedException {
        List<String> violations = new ArrayList<>();
        int started = 0;
        for (int padding = 0; padding < 60; padding++) {
            StateMachine<Cell, CellStatus, CellTransition> cells = CellChains.cells(padding, true, Integer.MAX_VALUE);
            for (int warm = 0; warm < 50; warm++) {
                cells.execute(CellChains.chain(3)[0], CellTransition.GO);
            }
            Cell[] chain = CellChains.chain(200_000);
            AtomicReference<Outcome<Cell, CellStatus, CellTransition>> outcome = new AtomicReference<>();
            Throwable thrown = CellChains.onStack(256 * 1024,
                    () -> outcome.set(cells.execute(chain[0], CellTransition.GO)));
            Optional<Cell> caughtOnceStarted = Optional.empty();
            for (int index = 0; index + 1 < chain.length && caughtOnceStarted.isEmpty(); index++) {
                if (chain[index].caught && chain[index + 1].identified) {
                    caughtOnceStarted = Optional.of(chain[index]);
                }
            }
            if (caughtOnceStarted.isEmpty()) {
                continue;
            }
            started++;
            long done = Stream.of(chain).filter((Cell cell) -> cell.status() == CellStatus.DONE).count();
            if (outcome.get() != null || done > 0) {
                violations.add("padding " + padding + ": " + caughtOnceStarted.get() + " caught the Error of a"
                        + " cascade that read the next cell's identity, yet the root " + (outcome.get() != null
                        ? "returned " + outcome.get().code() : "threw " + thrown) + " and " + done + " cells are DONE");
            }
        }
        assertEquals(List.of(), violations.size() > 3 ? violations.subList(0, 3) : violations);
        assertTrue(started > 0, "no action caught the Error of a cascade that had started");
    }
}
