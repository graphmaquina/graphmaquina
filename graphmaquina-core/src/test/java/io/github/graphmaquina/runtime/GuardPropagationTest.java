package io.github.graphmaquina.runtime;

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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.AutomaticStep;
import io.github.graphmaquina.api.CallFailure;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.MachineCall;
import io.github.graphmaquina.api.MachineObserver;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.OutcomeStatus;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.TransitionGuard;
import io.github.graphmaquina.dsl.GraphBuilder;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocMachines;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * A guard that declares a throwable type in {@code propagates} lets an instance of it reach the caller as itself, when
 * the guard belongs to the root step of an operation, an {@code execute}, a choice or the attempt an {@code advance}
 * makes, or to a check or a choice check: the call aborts as before, its observers hear of the abort, and nothing is
 * written. A type it does not declare, the guard of a cascaded step and the attempts of an automatic chain keep the
 * abort.
 */
class GuardPropagationTest {

    /** The failure of the service a guard reads. */
    static final class KycUnavailable extends RuntimeException {
        KycUnavailable(String message) {
            super(message);
        }
    }

    private static final KycUnavailable DOWN = new KycUnavailable("kyc unavailable");

    private static final List<Class<? extends Throwable>> PROPAGATES = List.of(KycUnavailable.class);

    private static final TransitionGuard<Doc, DocStatus, DocTransition> THROWING =
            (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                throw DOWN;
            };

    private static StateMachine<Doc, DocStatus, DocTransition> declaring() {
        return DocMachines.registry(docs((TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                .guard("kycReady", THROWING, PROPAGATES))).of(DOCS);
    }

    @Test
    void anOperationRethrowsADeclaredType() {
        assertSame(DOWN, assertThrows(KycUnavailable.class,
                () -> declaring().execute(new Doc("1", DRAFT), SUBMIT)));
    }

    @Test
    void aChoiceRethrowsADeclaredType() {
        assertSame(DOWN, assertThrows(KycUnavailable.class,
                () -> declaring().choose(new Doc("1", DRAFT), List.of(SUBMIT, DISCARD))));
    }

    @Test
    void advanceRethrowsADeclaredTypeOfItsOwnAttempt() {
        StateMachine<Doc, DocStatus, DocTransition> machine = DocMachines.registry(docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.automatic()
                        .guard("kycReady", THROWING, PROPAGATES))).of(DOCS);

        assertSame(DOWN, assertThrows(KycUnavailable.class, () -> machine.advance(new Doc("1", DRAFT))));
    }

    @Test
    void aCheckRethrowsADeclaredType() {
        assertSame(DOWN, assertThrows(KycUnavailable.class,
                () -> declaring().eligibility(new Doc("1", DRAFT), SUBMIT)));
    }

    @Test
    void aChoiceCheckRethrowsADeclaredType() {
        assertSame(DOWN, assertThrows(KycUnavailable.class,
                () -> declaring().chooseEligibility(new Doc("1", DRAFT), List.of(SUBMIT, DISCARD))));
    }

    @Test
    void anAsyncStageWithADeclaredCheckedTypeIsRethrown() {
        IOException offline = new IOException("offline");
        StateMachine<Doc, DocStatus, DocTransition> machine = DocMachines.registry(docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.asyncGuard("remote",
                        (GuardContext<Doc, DocStatus, DocTransition> ctx) ->
                                CompletableFuture.<GuardResult>failedFuture(offline),
                        List.of(IOException.class))), Duration.ofSeconds(10)).of(DOCS);

        assertSame(offline, assertThrows(IOException.class, () -> machine.execute(new Doc("1", DRAFT), SUBMIT)));
    }

    @Test
    void anUndeclaredTypeStaysWrapped() {
        IllegalStateException other = new IllegalStateException("other");
        StateMachine<Doc, DocStatus, DocTransition> machine = DocMachines.registry(docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.guard("kycReady",
                        (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                            throw other;
                        }, PROPAGATES))).of(DOCS);

        TransitionAbortedException abort = assertThrows(TransitionAbortedException.class,
                () -> machine.execute(new Doc("1", DRAFT), SUBMIT));

        assertSame(other, abort.guardFailure().orElseThrow());
    }

    @Test
    void aCascadedGuardStaysWrappedEvenWhenDeclared() {
        Doc peer = new Doc("2", REVIEW);
        GraphBuilder<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted or discarded")
                .statePolicy(REVIEW, "a review is published")
                .statePolicy(PUBLISHED, "a publication is archived");
        GraphDefinition<Doc, DocStatus, DocTransition> definition = graph
                .transition(SUBMIT, DRAFT, REVIEW)
                .action("publishPeer", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                    ctx.cascade(DOCS, peer, PUBLISH);
                    return ActionOutcome.ok();
                })
                .endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).guard("kycReady", THROWING, PROPAGATES).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
        StateMachine<Doc, DocStatus, DocTransition> machine = DocMachines.registry(definition).of(DOCS);

        TransitionAbortedException abort = assertThrows(TransitionAbortedException.class,
                () -> machine.execute(new Doc("1", DRAFT), SUBMIT));

        assertSame(DOWN, abort.guardFailure().orElseThrow());
    }

    @Test
    void theObserverStillHearsTheAbort() {
        List<CallFailure> heard = new ArrayList<>();
        StateMachine<Doc, DocStatus, DocTransition> machine = MachineRegistry.builder()
                .add(StateMachines.from(docs((TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .guard("kycReady", THROWING, PROPAGATES)))
                        .state(Doc::status, Doc::setStatus).identity(Doc::id))
                .observer(new MachineObserver() {
                    @Override
                    public void onFailed(MachineCall call, CallFailure failure, Duration elapsed) {
                        heard.add(failure);
                    }
                })
                .build().of(DOCS);

        assertThrows(KycUnavailable.class, () -> machine.execute(new Doc("1", DRAFT), SUBMIT));

        assertEquals(1, heard.size());
        TransitionAbortedException abort = assertInstanceOf(TransitionAbortedException.class, heard.get(0).error());
        assertSame(DOWN, abort.guardFailure().orElseThrow());
    }

    @Test
    void theUnitOfWorkIsDiscarded() {
        AtomicInteger writes = new AtomicInteger();
        StateMachine<Doc, DocStatus, DocTransition> machine = DocMachines.registry(
                docs((TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .guard("kycReady", THROWING, PROPAGATES)),
                (Doc doc, DocStatus status) -> {
                    writes.incrementAndGet();
                    doc.setStatus(status);
                }).of(DOCS);
        Doc doc = new Doc("1", DRAFT);

        assertThrows(KycUnavailable.class, () -> machine.execute(doc, SUBMIT));

        assertEquals(DRAFT, doc.status());
        assertEquals(0, writes.get());
    }

    @Test
    void aChainAttemptAfterACommitNeverThrows() {
        GraphDefinition<Doc, DocStatus, DocTransition> definition = StateGraph.define(DOCS).initial(DRAFT)
                .terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted or discarded")
                .statePolicy(REVIEW, "a review is published")
                .statePolicy(PUBLISHED, "a publication is archived")
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).automatic().guard("kycReady", THROWING, PROPAGATES)
                .endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
        StateMachine<Doc, DocStatus, DocTransition> machine = DocMachines.registry(definition).of(DOCS);

        Outcome<Doc, DocStatus, DocTransition> outcome = machine.execute(new Doc("1", DRAFT), SUBMIT);

        assertEquals(OutcomeStatus.APPLIED, outcome.status());
        AutomaticStep.Aborted aborted = outcome.automaticAbort().orElseThrow();
        assertTrue(aborted.cause() instanceof TransitionAbortedException);
    }
}
