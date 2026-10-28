package io.github.graphmaquina.dsl;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.FailureKind;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.RecoveryRule;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.error.InvalidGraphException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * What the DSL records for the automatic vocabulary: the {@code automatic()} mark, the continuing failure routes, and
 * the order in which {@code automaticFrom} answers the automatic edges of a state.
 */
class AutomaticDslTest {

    private static final String WHERE = "DRAFT -SUBMIT-> REVIEW";

    private static final TransitionAction<Doc, DocStatus, DocTransition> SUCCEEDS =
            (TransitionContext<Doc, DocStatus, DocTransition> context) -> ActionOutcome.ok();

    /** A failure kind of this test's own. */
    record Declined() implements FailureKind { }

    /** A second failure kind. */
    record Declined2() implements FailureKind { }

    /** A third failure kind. */
    record Declined3() implements FailureKind { }

    /**
     * DRAFT -SUBMIT-> REVIEW, shaped by {@code submit}; DRAFT -DISCARD-> ARCHIVED, shaped by {@code discard}; then
     * the plain REVIEW -PUBLISH-> PUBLISHED and PUBLISHED -ARCHIVE-> ARCHIVED. {@code order} is applied to the graph
     * before it is built.
     */
    private static GraphDefinition<Doc, DocStatus, DocTransition> docs(
            UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>> submit,
            UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>> discard,
            List<DocTransition> order) {
        GraphBuilder<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted or discarded")
                .statePolicy(REVIEW, "a review is published")
                .statePolicy(PUBLISHED, "a publication is archived");
        graph = submit.apply(graph.transition(SUBMIT, DRAFT, REVIEW)).endTransition();
        graph = discard.apply(graph.transition(DISCARD, DRAFT, ARCHIVED)).endTransition();
        if (!order.isEmpty()) {
            graph = graph.automaticOrder(DRAFT, order);
        }
        return graph.transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
    }

    private static TransitionBuilder<Doc, DocStatus, DocTransition> plain(
            TransitionBuilder<Doc, DocStatus, DocTransition> edge) {
        return edge;
    }

    private static TransitionBuilder<Doc, DocStatus, DocTransition> automatic(
            TransitionBuilder<Doc, DocStatus, DocTransition> edge) {
        return edge.automatic();
    }

    private static InvalidGraphException refused(
            UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>> submit) {
        return assertThrows(InvalidGraphException.class, () -> docs(submit, AutomaticDslTest::plain, List.of()));
    }

    @Test
    void automaticMarksTheEdge() {
        GraphDefinition<Doc, DocStatus, DocTransition> graph =
                docs(AutomaticDslTest::automatic, AutomaticDslTest::plain, List.of());
        assertTrue(graph.findTransition(DRAFT, SUBMIT).orElseThrow().automatic());
        assertFalse(graph.findTransition(DRAFT, DISCARD).orElseThrow().automatic());
        assertFalse(graph.findTransition(DRAFT, SUBMIT).orElseThrow().onFailureContinues());
    }

    @Test
    void sg507ASecondAutomaticMark() {
        InvalidGraphException error = refused(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.automatic().automatic());
        assertEquals(List.of(new InvalidGraphException.Problem(DiagnosticCode.SG507.name(),
                WHERE + " declares 2 automatic marks, but an edge has at most one")), error.problems());
    }

    @Test
    void onFailureAndContinueIsTheOnFailure() {
        TransitionSpec<Doc, DocStatus, DocTransition> submit = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> edge) -> edge.action("submit", SUCCEEDS)
                        .onFailureAndContinue(DISCARD),
                AutomaticDslTest::plain, List.of()).findTransition(DRAFT, SUBMIT).orElseThrow();
        assertEquals(DISCARD, submit.onFailure().orElseThrow());
        assertTrue(submit.onFailureContinues());
    }

    @Test
    void sg507OnFailureAndContinueCombinedWithOnFailure() {
        InvalidGraphException error = refused(
                (TransitionBuilder<Doc, DocStatus, DocTransition> edge) -> edge.action("submit", SUCCEEDS)
                        .onFailure(DISCARD).onFailureAndContinue(DISCARD));
        assertEquals(List.of(new InvalidGraphException.Problem(DiagnosticCode.SG507.name(),
                WHERE + " declares 2 onFailure routes, but an edge has at most one")), error.problems());
    }

    @Test
    void recoverAndContinueMarksTheRule() {
        TransitionSpec<Doc, DocStatus, DocTransition> submit = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> edge) -> edge.action("submit", SUCCEEDS)
                        .recover(Declined.class, DISCARD)
                        .recoverAndContinue(Declined2.class, DISCARD)
                        .recoverAndContinue(Declined3.class, DISCARD, List.of(IllegalStateException.class)),
                AutomaticDslTest::plain, List.of()).findTransition(DRAFT, SUBMIT).orElseThrow();
        List<RecoveryRule<DocTransition>> rules = submit.recoveryRules();
        assertEquals(3, rules.size());
        assertFalse(rules.get(0).continueChain());
        assertTrue(rules.get(1).continueChain());
        assertTrue(rules.get(2).continueChain());
        assertEquals(List.of(IllegalStateException.class), rules.get(2).failsOn());
    }

    @Test
    void automaticFromFollowsTheDeclaredOrderNotTheEnum() {
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(AutomaticDslTest::automatic,
                AutomaticDslTest::automatic, List.of(DISCARD, SUBMIT));
        assertEquals(List.of(SUBMIT, DISCARD), identifiers(graph.outgoingFrom(DRAFT)));
        assertEquals(List.of(DISCARD, SUBMIT), identifiers(graph.automaticFrom(DRAFT)));
    }

    @Test
    void automaticFromWithOneEdgeNeedsNoOrder() {
        GraphDefinition<Doc, DocStatus, DocTransition> graph =
                docs(AutomaticDslTest::automatic, AutomaticDslTest::plain, List.of());
        assertEquals(List.of(SUBMIT), identifiers(graph.automaticFrom(DRAFT)));
    }

    @Test
    void automaticFromIsEmptyWithoutAutomaticEdges() {
        GraphDefinition<Doc, DocStatus, DocTransition> graph =
                docs(AutomaticDslTest::plain, AutomaticDslTest::plain, List.of());
        assertEquals(List.of(), graph.automaticFrom(DRAFT));
        assertEquals(List.of(), graph.automaticFrom(REVIEW));
        assertThrows(UnsupportedOperationException.class,
                () -> graph.automaticFrom(DRAFT).add(graph.findTransition(DRAFT, SUBMIT).orElseThrow()));
    }

    @Test
    void automaticFromListsOnlyAutomaticEdgesWhateverTheOrderNames() {
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(AutomaticDslTest::plain,
                AutomaticDslTest::automatic, List.of(PUBLISH, SUBMIT, DISCARD));
        assertEquals(List.of(DISCARD), identifiers(graph.automaticFrom(DRAFT)));
    }

    @Test
    void automaticFromAppendsTheAutomaticEdgesTheOrderDoesNotName() {
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(AutomaticDslTest::automatic,
                AutomaticDslTest::automatic, List.of(DISCARD));
        assertEquals(List.of(DISCARD, SUBMIT), identifiers(graph.automaticFrom(DRAFT)));
    }

    private static List<DocTransition> identifiers(List<TransitionSpec<Doc, DocStatus, DocTransition>> edges) {
        return edges.stream().map(TransitionSpec::transition).toList();
    }
}
