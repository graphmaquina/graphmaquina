package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.ARCHIVE;
import static io.github.graphmaquina.fixtures.DocTransition.DISCARD;
import static io.github.graphmaquina.fixtures.DocTransition.PUBLISH;
import static io.github.graphmaquina.fixtures.DocTransition.RETOUCH;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.ChoiceEligibility;
import io.github.graphmaquina.api.Eligibility;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.OutcomeStatus;
import io.github.graphmaquina.api.RejectionKind;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocMachines;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * A gate is a self-loop that only a check asks: a check, a choice check and the selector's {@code eligibility} run its
 * guards and its state's policy as for any edge, while a request of its identifier, an {@code execute}, a candidate of
 * a running choice or a cascade, answers as if the state had no edge for it. It is no cycle, so it needs no
 * {@code allowsCycle}, and it never commits.
 */
class GateEdgeTest {

    private static final String REVIEW_POLICY = "a review is published";

    private static GraphDefinition<Doc, DocStatus, DocTransition> graph() {
        return StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted or discarded")
                .statePolicy(REVIEW, REVIEW_POLICY)
                .statePolicy(PUBLISHED, "a publication is archived")
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED).endTransition()
                .transition(RETOUCH, REVIEW, REVIEW).gate()
                .guard("unlocked", (GuardContext<Doc, DocStatus, DocTransition> ctx) ->
                        "locked".equals(ctx.entity().id())
                                ? GuardResult.reject("the doc is locked") : GuardResult.pass())
                .endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
    }

    private static StateMachine<Doc, DocStatus, DocTransition> machine() {
        return DocMachines.registry(graph()).of(DOCS);
    }

    @Test
    void aGateNeedsNoAllowsCycle() {
        GraphDefinition<Doc, DocStatus, DocTransition> graph = graph();

        assertTrue(graph.findTransition(REVIEW, RETOUCH).orElseThrow().gate());
    }

    @Test
    void aCheckOfAGateRunsItsGuardsAndPolicy() {
        StateMachine<Doc, DocStatus, DocTransition> machine = machine();

        assertTrue(machine.eligibility(new Doc("1", REVIEW), RETOUCH).isEligible());
        Eligibility.Ineligible<Doc, DocStatus, DocTransition> locked =
                machine.eligibility(new Doc("locked", REVIEW), RETOUCH).asIneligible().orElseThrow();
        assertEquals(RejectionKind.GUARD, locked.kind());
        assertEquals("the doc is locked", locked.reason());
        Eligibility.Ineligible<Doc, DocStatus, DocTransition> draft =
                machine.eligibility(new Doc("1", DRAFT), RETOUCH).asIneligible().orElseThrow();
        assertEquals(RejectionKind.NO_TRANSITION, draft.kind());
    }

    @Test
    void executeOfAGateIsNoTransition() {
        Doc doc = new Doc("1", REVIEW);

        Outcome.Rejected<Doc, DocStatus, DocTransition> rejected =
                machine().execute(doc, RETOUCH).asRejected().orElseThrow();

        assertEquals(RejectionKind.NO_TRANSITION, rejected.kind());
        assertEquals(REVIEW_POLICY, rejected.reason());
        assertEquals(REVIEW, doc.status());
    }

    @Test
    void aChoiceCheckMayChooseAGate() {
        ChoiceEligibility<Doc, DocStatus, DocTransition> answer =
                machine().chooseEligibility(new Doc("1", REVIEW), List.of(RETOUCH, PUBLISH));

        assertEquals(RETOUCH, answer.chosen().orElseThrow());
    }

    @Test
    void aRuntimeChooseSkipsAGateAsNoTransition() {
        Doc doc = new Doc("1", REVIEW);

        Outcome<Doc, DocStatus, DocTransition> outcome = machine().choose(doc, List.of(RETOUCH, PUBLISH));

        assertEquals(OutcomeStatus.APPLIED, outcome.status());
        assertEquals(PUBLISH, outcome.requestedTransition().orElseThrow());
        assertEquals(PUBLISHED, doc.status());
    }

    @Test
    void aCascadeToAGateIsNoTransition() {
        Doc peer = new Doc("2", REVIEW);
        GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS).initial(DRAFT)
                .terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted or discarded")
                .statePolicy(REVIEW, REVIEW_POLICY)
                .statePolicy(PUBLISHED, "a publication is archived")
                .transition(SUBMIT, DRAFT, REVIEW)
                .action("retouchPeer", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                    ctx.cascade(DOCS, peer, RETOUCH);
                    return ActionOutcome.ok();
                })
                .endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED).endTransition()
                .transition(RETOUCH, REVIEW, REVIEW).gate().endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
        StateMachine<Doc, DocStatus, DocTransition> machine = DocMachines.registry(graph).of(DOCS);

        TransitionAbortedException abort = assertThrows(TransitionAbortedException.class,
                () -> machine.execute(new Doc("1", DRAFT), SUBMIT));

        assertTrue(abort.getMessage().contains("no such transition"), abort.getMessage());
    }

    @Test
    void aGateDoesNotCommitOrChain() {
        AtomicInteger writes = new AtomicInteger();
        StateMachine<Doc, DocStatus, DocTransition> machine = DocMachines.registry(graph(),
                (Doc doc, DocStatus status) -> {
                    writes.incrementAndGet();
                    doc.setStatus(status);
                }).of(DOCS);

        Outcome<Doc, DocStatus, DocTransition> outcome = machine.execute(new Doc("1", REVIEW), RETOUCH);

        assertEquals(OutcomeStatus.REJECTED, outcome.status());
        assertEquals(0, writes.get());
        assertTrue(outcome.automaticSteps().isEmpty());
    }
}
