package io.github.graphmaquina.dsl;

import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.ARCHIVE;
import static io.github.graphmaquina.fixtures.DocTransition.PUBLISH;
import static io.github.graphmaquina.fixtures.DocTransition.RETOUCH;
import static io.github.graphmaquina.fixtures.DocTransition.SEND_BACK;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.AutomaticChain;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.TransitionKind;
import io.github.graphmaquina.error.InvalidGraphException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * The build rules of gates and external edges: a gate is a self-loop with guards at most (SG111), an external edge a
 * plain edge to another state (SG112), all edges of one identifier are of one kind (SG113), and no failure route,
 * exit or watch rule names a gate or an external edge (SG114). A gate is no cycle and no exit of a non-terminal state,
 * while an external edge counts for both; a second gate or external mark is SG507.
 */
class EdgeKindValidationTest {

    private static GraphBuilder<Doc, DocStatus, DocTransition> docs() {
        return StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted")
                .statePolicy(REVIEW, "a review is published")
                .statePolicy(PUBLISHED, "a publication is archived");
    }

    /** DRAFT submits, REVIEW publishes, PUBLISHED archives; {@code extra} adds the edges under test. */
    private static GraphBuilder<Doc, DocStatus, DocTransition> with(
            UnaryOperator<GraphBuilder<Doc, DocStatus, DocTransition>> extra) {
        return extra.apply(docs()
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition());
    }

    private static List<String> codes(GraphBuilder<Doc, DocStatus, DocTransition> builder) {
        return assertThrows(InvalidGraphException.class, builder::build).codes();
    }

    private static final List<String> GATE_FAULT = List.of(DiagnosticCode.SG111.name());
    private static final List<String> EXTERNAL_FAULT = List.of(DiagnosticCode.SG112.name());

    @Test
    void sg111AGateThatIsNoSelfLoop() {
        assertEquals(GATE_FAULT, codes(with((GraphBuilder<Doc, DocStatus, DocTransition> graph) ->
                graph.transition(RETOUCH, REVIEW, PUBLISHED).gate().endTransition())));
    }

    @Test
    void sg111AGateWithAnAction() {
        assertEquals(GATE_FAULT, codes(with((GraphBuilder<Doc, DocStatus, DocTransition> graph) ->
                graph.transition(RETOUCH, REVIEW, REVIEW).gate()
                        .action("touch", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> ActionOutcome.ok())
                        .endTransition())));
    }

    @Test
    void sg111AnAutomaticGate() {
        assertEquals(GATE_FAULT, codes(with((GraphBuilder<Doc, DocStatus, DocTransition> graph) ->
                graph.transition(RETOUCH, REVIEW, REVIEW).gate().automatic().endTransition())));
    }

    @Test
    void sg111AGateWithAHook() {
        assertEquals(GATE_FAULT, codes(with((GraphBuilder<Doc, DocStatus, DocTransition> graph) ->
                graph.transition(RETOUCH, REVIEW, REVIEW).gate().afterCommit("notify", (Doc doc) -> { })
                        .endTransition())));
    }

    @Test
    void sg111AGateThatIsAlsoExternal() {
        assertEquals(GATE_FAULT, codes(with((GraphBuilder<Doc, DocStatus, DocTransition> graph) ->
                graph.transition(RETOUCH, REVIEW, REVIEW).gate().external().endTransition())));
    }

    @Test
    void sg112AnExternalSelfLoop() {
        assertEquals(EXTERNAL_FAULT, codes(with((GraphBuilder<Doc, DocStatus, DocTransition> graph) ->
                graph.transition(RETOUCH, REVIEW, REVIEW).external().endTransition().allowsCycle(REVIEW))));
    }

    @Test
    void sg112AnExternalEdgeWithAGuard() {
        assertEquals(EXTERNAL_FAULT, codes(with((GraphBuilder<Doc, DocStatus, DocTransition> graph) ->
                graph.transition(SEND_BACK, PUBLISHED, DRAFT).external()
                        .guard("open", (GuardContext<Doc, DocStatus, DocTransition> ctx) -> GuardResult.pass())
                        .endTransition()
                        .allowsCycle(DRAFT))));
    }

    @Test
    void sg112AnExternalEdgeWithAnAction() {
        assertEquals(EXTERNAL_FAULT, codes(with((GraphBuilder<Doc, DocStatus, DocTransition> graph) ->
                graph.transition(SEND_BACK, PUBLISHED, DRAFT).external()
                        .action("touch", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> ActionOutcome.ok())
                        .endTransition()
                        .allowsCycle(DRAFT))));
    }

    @Test
    void sg112AnAutomaticExternalEdge() {
        assertEquals(EXTERNAL_FAULT, codes(with((GraphBuilder<Doc, DocStatus, DocTransition> graph) ->
                graph.transition(SEND_BACK, PUBLISHED, DRAFT).external().automatic().endTransition()
                        .allowsCycle(DRAFT))));
    }

    @Test
    void sg112AnExternalEdgeWithAHook() {
        assertEquals(EXTERNAL_FAULT, codes(with((GraphBuilder<Doc, DocStatus, DocTransition> graph) ->
                graph.transition(SEND_BACK, PUBLISHED, DRAFT).external().afterCommit("notify", (Doc doc) -> { })
                        .endTransition()
                        .allowsCycle(DRAFT))));
    }

    @Test
    void sg113AnIdentifierOfTwoKinds() {
        assertEquals(List.of(DiagnosticCode.SG113.name()), codes(with(
                (GraphBuilder<Doc, DocStatus, DocTransition> graph) -> graph
                        .transition(RETOUCH, REVIEW, REVIEW).gate().endTransition()
                        .transition(RETOUCH, DRAFT, PUBLISHED).endTransition())));
    }

    /**
     * DRAFT submits, REVIEW is gated by RETOUCH and finishes, DONE is terminal; {@code watches} and
     * {@code submachines} are the rules under test.
     */
    private static List<String> outlineCodes(List<WatchOutline> watches, List<SubmachineOutline> submachines) {
        TransitionOutline gate = new TransitionOutline("REVIEW", "RETOUCH", "REVIEW", TransitionKind.MOVE,
                Optional.empty(), List.of(), false, false, false, List.of(), true, false, false);
        MachineOutline outline = new MachineOutline("docs", List.of("DRAFT", "REVIEW", "DONE"),
                List.of("SUBMIT", "RETOUCH", "FINISH"), "DRAFT", Set.of("DONE"), Set.of(),
                List.of(new TransitionOutline("DRAFT", "SUBMIT", "REVIEW", TransitionKind.MOVE, Optional.empty()), gate,
                        new TransitionOutline("REVIEW", "FINISH", "DONE", TransitionKind.MOVE, Optional.empty())),
                List.of(new PolicyOutline("DRAFT", "a draft is submitted", Map.of()),
                        new PolicyOutline("REVIEW", "a review finishes", Map.of()),
                        new PolicyOutline("DONE", "a finished doc stays", Map.of())),
                watches, submachines, List.of(), AutomaticChain.AFTER_COMMIT);
        return MachineOutlineValidator.validate(outline).stream().map(InvalidGraphException.Problem::code).toList();
    }

    private static final List<String> UNTAKEABLE = List.of(DiagnosticCode.SG114.name());

    @Test
    void sg114AWatchRuleThatExecutesAGate() {
        assertEquals(UNTAKEABLE, outlineCodes(List.of(new WatchOutline("REVIEW", List.of("RETOUCH"))), List.of()));
    }

    @Test
    void sg114AnExitThatNamesAGate() {
        assertEquals(UNTAKEABLE, outlineCodes(List.of(), List.of(new SubmachineOutline("REVIEW", "checks",
                List.of("PASSED"), List.of(new ExitOutline("PASSED", "RETOUCH"))))));
    }

    @Test
    void sg114AFailureRouteThatNamesAGate() {
        assertEquals(UNTAKEABLE, codes(docs()
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(RETOUCH, REVIEW, REVIEW).gate().endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED)
                .action("publish", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> ActionOutcome.ok())
                .onFailure(RETOUCH)
                .endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()));
    }

    @Test
    void sg114AFailureRouteThatNamesAnExternalEdge() {
        assertEquals(UNTAKEABLE, codes(docs()
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED)
                .action("archive", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> ActionOutcome.ok())
                .onFailure(SEND_BACK)
                .endTransition()
                .transition(SEND_BACK, PUBLISHED, DRAFT).external().endTransition()
                .allowsCycle(DRAFT)));
    }

    @Test
    void aStateWhoseOnlyEdgeIsAGateIsStillSg202() {
        assertEquals(List.of(DiagnosticCode.SG202.name()), codes(docs()
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(RETOUCH, REVIEW, REVIEW).gate().endTransition()
                .transition(PUBLISH, DRAFT, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()));
    }

    @Test
    void aGateOnATerminalIsSg202() {
        assertEquals(List.of(DiagnosticCode.SG202.name()), codes(with(
                (GraphBuilder<Doc, DocStatus, DocTransition> graph) -> graph
                        .transition(RETOUCH, ARCHIVED, ARCHIVED).gate().endTransition())));
    }

    @Test
    void anExternalEdgeCountsForCycles() {
        assertEquals(List.of(DiagnosticCode.SG204.name()), codes(with(
                (GraphBuilder<Doc, DocStatus, DocTransition> graph) -> graph
                        .transition(SEND_BACK, PUBLISHED, DRAFT).external().endTransition())));
    }

    @Test
    void aSecondGateMarkIsSg507() {
        assertEquals(List.of(DiagnosticCode.SG507.name()), codes(with(
                (GraphBuilder<Doc, DocStatus, DocTransition> graph) -> graph
                        .transition(RETOUCH, REVIEW, REVIEW).gate().gate().endTransition())));
    }
}
