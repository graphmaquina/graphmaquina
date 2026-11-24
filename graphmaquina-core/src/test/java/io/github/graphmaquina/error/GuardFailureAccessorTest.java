package io.github.graphmaquina.error;

import static io.github.graphmaquina.fixtures.DocGraphs.docs;
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
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocMachines;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * {@link StateMachineException#guardFailure()} gives what a guard itself threw, or completed its stage with, once
 * unwrapped: on the abort of a call, whatever the depth of the guard's step, and on the failure of a check. A guard
 * that answered null or a null stage, a wait past the deadline or ended by an interrupt, and an action's failure give
 * none: the engine made those, the guard did not throw them.
 */
class GuardFailureAccessorTest {

    private static final RuntimeException KYC_DOWN = new IllegalStateException("kyc unavailable");

    private static StateMachine<Doc, DocStatus, DocTransition> machine(
            UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>> submit) {
        return DocMachines.registry(docs(submit)).of(DOCS);
    }

    private static StateMachine<Doc, DocStatus, DocTransition> timed(
            UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>> submit, Duration timeout) {
        return DocMachines.registry(docs(submit), timeout).of(DOCS);
    }

    private static StateMachine<Doc, DocStatus, DocTransition> throwingGuard() {
        return machine((TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                .guard("kycReady", (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                    throw KYC_DOWN;
                }));
    }

    @Test
    void aSyncGuardThatThrowsGivesItsThrowable() {
        TransitionAbortedException abort = assertThrows(TransitionAbortedException.class,
                () -> throwingGuard().execute(new Doc("1", DRAFT), SUBMIT));

        assertEquals(CallPhase.GUARD, abort.phase());
        assertSame(KYC_DOWN, abort.guardFailure().orElseThrow());
        assertEquals("kycReady", abort.guard().orElseThrow());
    }

    @Test
    void anAsyncGuardStageThatFailsGivesItsThrowable() {
        StateMachine<Doc, DocStatus, DocTransition> machine = timed(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.asyncGuard("remote",
                        (GuardContext<Doc, DocStatus, DocTransition> ctx) ->
                                CompletableFuture.<GuardResult>failedFuture(new CompletionException(KYC_DOWN))),
                Duration.ofSeconds(10));

        TransitionAbortedException abort = assertThrows(TransitionAbortedException.class,
                () -> machine.execute(new Doc("1", DRAFT), SUBMIT));

        assertSame(KYC_DOWN, abort.guardFailure().orElseThrow());
    }

    @Test
    void aCheckWhoseGuardThrowsGivesItsThrowable() {
        EligibilityCheckException failure = assertThrows(EligibilityCheckException.class,
                () -> throwingGuard().eligibility(new Doc("1", DRAFT), SUBMIT));

        assertSame(KYC_DOWN, failure.guardFailure().orElseThrow());
        assertEquals("kycReady", failure.guard().orElseThrow());
    }

    @Test
    void aCascadedGuardThatThrowsIsMarkedToo() {
        Doc peer = new Doc("2", REVIEW);
        GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS).initial(DRAFT)
                .terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted or discarded")
                .statePolicy(REVIEW, "a review is published")
                .statePolicy(PUBLISHED, "a publication is archived")
                .transition(SUBMIT, DRAFT, REVIEW)
                .action("publishPeer", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                    ctx.cascade(DOCS, peer, PUBLISH);
                    return ActionOutcome.ok();
                })
                .endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED)
                .guard("kycReady", (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                    throw KYC_DOWN;
                })
                .endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
        StateMachine<Doc, DocStatus, DocTransition> machine = DocMachines.registry(graph).of(DOCS);

        TransitionAbortedException abort = assertThrows(TransitionAbortedException.class,
                () -> machine.execute(new Doc("1", DRAFT), SUBMIT));

        assertSame(KYC_DOWN, abort.guardFailure().orElseThrow());
    }

    @Test
    void nullVerdictNullStageDeadlineAndInterruptGiveNone() {
        StateMachine<Doc, DocStatus, DocTransition> nullVerdict = machine(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .guard("silent", (GuardContext<Doc, DocStatus, DocTransition> ctx) -> null));
        StateMachine<Doc, DocStatus, DocTransition> nullStage = timed(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .asyncGuard("silent", (GuardContext<Doc, DocStatus, DocTransition> ctx) -> null),
                Duration.ofSeconds(10));
        StateMachine<Doc, DocStatus, DocTransition> pending = timed(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .asyncGuard("pending", (GuardContext<Doc, DocStatus, DocTransition> ctx) ->
                                new CompletableFuture<GuardResult>()),
                Duration.ofMillis(50));

        assertTrue(assertThrows(TransitionAbortedException.class,
                () -> nullVerdict.execute(new Doc("1", DRAFT), SUBMIT)).guardFailure().isEmpty());
        assertTrue(assertThrows(TransitionAbortedException.class,
                () -> nullStage.execute(new Doc("1", DRAFT), SUBMIT)).guardFailure().isEmpty());
        assertTrue(assertThrows(TransitionAbortedException.class,
                () -> pending.execute(new Doc("1", DRAFT), SUBMIT)).guardFailure().isEmpty());
        StateMachine<Doc, DocStatus, DocTransition> interrupted = timed(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .asyncGuard("pending", (GuardContext<Doc, DocStatus, DocTransition> ctx) ->
                                new CompletableFuture<GuardResult>()),
                Duration.ofSeconds(10));
        Thread.currentThread().interrupt();
        try {
            assertTrue(assertThrows(TransitionAbortedException.class,
                    () -> interrupted.execute(new Doc("1", DRAFT), SUBMIT)).guardFailure().isEmpty());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void anActionFailureGivesNone() {
        StateMachine<Doc, DocStatus, DocTransition> machine = machine(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("register", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            throw KYC_DOWN;
                        }));

        TransitionAbortedException abort = assertThrows(TransitionAbortedException.class,
                () -> machine.execute(new Doc("1", DRAFT), SUBMIT));

        assertEquals(CallPhase.ACTION, abort.phase());
        assertTrue(abort.guardFailure().isEmpty());
        assertTrue(abort.guard().isEmpty());
    }
}
