package io.github.graphmaquina.dsl;

import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.ARCHIVE;
import static io.github.graphmaquina.fixtures.DocTransition.DISCARD;
import static io.github.graphmaquina.fixtures.DocTransition.PUBLISH;
import static io.github.graphmaquina.fixtures.DocTransition.RETOUCH;
import static io.github.graphmaquina.fixtures.DocTransition.SEND_BACK;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.FailureKind;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.TransitionGuard;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.error.InvalidGraphException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The build rules of the automatic vocabulary over the doc machine: the automatic order (SG108, SG109), the cycles of
 * the continuation graph (SG206) and the continuing failure routes (SG305). Every refusal is asserted as the only
 * problem the build reports. The rule about sub-machines (SG807) is in {@link AutomaticSubmachineValidationTest}.
 */
class AutomaticValidationTest {

    /** A failure kind of this test's own. */
    record Declined() implements FailureKind { }

    private static final TransitionAction<Doc, DocStatus, DocTransition> SUCCEEDS =
            (TransitionContext<Doc, DocStatus, DocTransition> context) -> ActionOutcome.ok();

    private static final TransitionGuard<Doc, DocStatus, DocTransition> OPEN =
            (GuardContext<Doc, DocStatus, DocTransition> context) -> GuardResult.pass();

    /** DRAFT is initial, ARCHIVED terminal, and the three other states reject by policy. */
    private static GraphBuilder<Doc, DocStatus, DocTransition> docs() {
        return StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted or discarded")
                .statePolicy(REVIEW, "a review is published or sent back")
                .statePolicy(PUBLISHED, "a publication is archived");
    }

    /**
     * Two automatic edges leave DRAFT, SUBMIT to REVIEW and DISCARD to ARCHIVED, and a third one, RETOUCH to
     * PUBLISHED, is manual. REVIEW publishes and PUBLISHED archives.
     */
    private static GraphBuilder<Doc, DocStatus, DocTransition> twoAutomaticFromDraft() {
        return docs()
                .transition(SUBMIT, DRAFT, REVIEW).automatic().endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED).automatic().endTransition()
                .transition(RETOUCH, DRAFT, PUBLISHED).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition();
    }

    private static List<InvalidGraphException.Problem> problems(GraphBuilder<Doc, DocStatus, DocTransition> builder) {
        return assertThrows(InvalidGraphException.class, builder::build).problems();
    }

    private static List<InvalidGraphException.Problem> only(DiagnosticCode code, String message) {
        return List.of(new InvalidGraphException.Problem(code.name(), message));
    }

    @Test
    void sg108NoOrderWithTwoAutomaticTransitions() {
        assertEquals(only(DiagnosticCode.SG108,
                "state DRAFT has 2 automatic transitions, SUBMIT and DISCARD, and declares no automaticOrder"),
                problems(twoAutomaticFromDraft()));
    }

    @Test
    void sg108OrderNamesAManualTransition() {
        assertEquals(only(DiagnosticCode.SG108,
                "the automaticOrder of DRAFT names RETOUCH, which is not an automatic transition from DRAFT"),
                problems(twoAutomaticFromDraft().automaticOrder(DRAFT, List.of(SUBMIT, RETOUCH, DISCARD))));
    }

    @Test
    void sg108OrderNamesAnIdentifierWithoutAnEdgeFromTheState() {
        assertEquals(only(DiagnosticCode.SG108,
                "the automaticOrder of DRAFT names SEND_BACK, which is not an automatic transition from DRAFT"),
                problems(twoAutomaticFromDraft().automaticOrder(DRAFT, List.of(SEND_BACK, SUBMIT, DISCARD))));
    }

    @Test
    void sg108OrderLeavesOutAnAutomaticTransition() {
        assertEquals(only(DiagnosticCode.SG108, "the automaticOrder of DRAFT does not name DISCARD"),
                problems(twoAutomaticFromDraft().automaticOrder(DRAFT, List.of(SUBMIT))));
    }

    @Test
    void sg108OrderNamesATransitionTwice() {
        assertEquals(only(DiagnosticCode.SG108, "the automaticOrder of DRAFT names SUBMIT twice"),
                problems(twoAutomaticFromDraft().automaticOrder(DRAFT, List.of(SUBMIT, DISCARD, SUBMIT))));
    }

    @Test
    void sg108OrderOnAStateWithOneAutomaticTransition() {
        GraphBuilder<Doc, DocStatus, DocTransition> graph = docs()
                .transition(SUBMIT, DRAFT, REVIEW).automatic().endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .automaticOrder(DRAFT, List.of(SUBMIT));
        assertEquals(only(DiagnosticCode.SG108,
                "state DRAFT declares an automaticOrder but has fewer than two automatic transitions"),
                problems(graph));
    }

    @Test
    void sg108OrderOnAStateWithoutAutomaticTransitions() {
        GraphBuilder<Doc, DocStatus, DocTransition> graph = docs()
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .automaticOrder(DRAFT, List.of(SUBMIT, DISCARD));
        assertEquals(only(DiagnosticCode.SG108,
                "state DRAFT declares an automaticOrder but has fewer than two automatic transitions"),
                problems(graph));
    }

    @Test
    void sg108TwoOrdersOnOneState() {
        assertEquals(only(DiagnosticCode.SG108, "state DRAFT declares 2 automaticOrders, but a state has at most one"),
                problems(twoAutomaticFromDraft().automaticOrder(DRAFT, List.of(SUBMIT, DISCARD))
                        .automaticOrder(DRAFT, List.of(DISCARD, SUBMIT))));
    }

    /** SUBMIT leaves DRAFT unguarded, DISCARD guarded; {@code order} says which one is tried first. */
    private static GraphBuilder<Doc, DocStatus, DocTransition> oneGuardedOneNot(List<DocTransition> order) {
        return docs()
                .transition(SUBMIT, DRAFT, REVIEW).automatic().endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED).automatic().guard("open", OPEN).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .automaticOrder(DRAFT, order);
    }

    @Test
    void sg109GuardlessFirst() {
        assertEquals(only(DiagnosticCode.SG109, "in the automaticOrder of DRAFT, SUBMIT has no guard and comes before "
                + "DISCARD, which could never be chosen"), problems(oneGuardedOneNot(List.of(SUBMIT, DISCARD))));
    }

    @Test
    void aGuardlessLastIsFine() {
        assertEquals(List.of(DISCARD, SUBMIT),
                identifiers(oneGuardedOneNot(List.of(DISCARD, SUBMIT)).build().automaticFrom(DRAFT)));
    }

    @Test
    void sg206AutomaticSelfLoop() {
        GraphBuilder<Doc, DocStatus, DocTransition> graph = docs().allowsCycle(REVIEW)
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(RETOUCH, REVIEW, REVIEW).automatic().endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition();
        assertEquals(only(DiagnosticCode.SG206, "the automatic transitions form a cycle: [REVIEW, REVIEW]"),
                problems(graph));
    }

    /**
     * A cycle of automatic edges that no allowsCycle names is first an unauthorized cycle; allowing it reveals that
     * the automatic edges form it.
     */
    @Test
    void fixingAnSg204RevealsAnSg206() {
        GraphBuilder<Doc, DocStatus, DocTransition> unallowed = docs()
                .transition(SUBMIT, DRAFT, REVIEW).automatic().endTransition()
                .transition(SEND_BACK, REVIEW, DRAFT).automatic().endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition();
        assertEquals(List.of(DiagnosticCode.SG204.name()),
                problems(unallowed).stream().map(InvalidGraphException.Problem::code).toList());
    }

    @Test
    void sg206CycleThroughAnAllowedState() {
        GraphBuilder<Doc, DocStatus, DocTransition> graph = docs().allowsCycle(DRAFT)
                .transition(SUBMIT, DRAFT, REVIEW).automatic().endTransition()
                .transition(SEND_BACK, REVIEW, DRAFT).automatic().endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition();
        assertEquals(only(DiagnosticCode.SG206, "the automatic transitions form a cycle: [DRAFT, REVIEW, DRAFT]"),
                problems(graph));
    }

    /**
     * SUBMIT is automatic and its action may fail into DISCARD, which continues the chain in REVIEW, where SEND_BACK
     * is automatic and returns to DRAFT. The automatic edges alone, SUBMIT and SEND_BACK, close no cycle.
     */
    private static GraphBuilder<Doc, DocStatus, DocTransition> routeBackToDraft() {
        return docs().allowsCycle(DRAFT).terminal(PUBLISHED)
                .transition(SUBMIT, DRAFT, ARCHIVED).automatic().action("submit", SUCCEEDS)
                        .recoverAndContinue(Declined.class, DISCARD).endTransition()
                .transition(DISCARD, DRAFT, REVIEW).endTransition()
                .transition(SEND_BACK, REVIEW, DRAFT).automatic().endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition();
    }

    @Test
    void sg206CycleClosedByAContinuingRoute() {
        assertEquals(only(DiagnosticCode.SG206, "the automatic transitions form a cycle: [DRAFT, REVIEW, DRAFT]"),
                problems(routeBackToDraft()));
    }

    @Test
    void sg206CycleClosedByAContinuingOnFailure() {
        GraphBuilder<Doc, DocStatus, DocTransition> graph = docs().allowsCycle(DRAFT).terminal(PUBLISHED)
                .transition(SUBMIT, DRAFT, ARCHIVED).automatic().action("submit", SUCCEEDS)
                        .onFailureAndContinue(DISCARD).endTransition()
                .transition(DISCARD, DRAFT, REVIEW).endTransition()
                .transition(SEND_BACK, REVIEW, DRAFT).automatic().endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition();
        assertEquals(only(DiagnosticCode.SG206, "the automatic transitions form a cycle: [DRAFT, REVIEW, DRAFT]"),
                problems(graph));
    }

    @Test
    void aRouteThatDoesNotContinueClosesNoAutomaticCycle() {
        GraphBuilder<Doc, DocStatus, DocTransition> graph = docs().allowsCycle(DRAFT).terminal(PUBLISHED)
                .transition(SUBMIT, DRAFT, ARCHIVED).automatic().action("submit", SUCCEEDS)
                        .recover(Declined.class, DISCARD).endTransition()
                .transition(DISCARD, DRAFT, REVIEW).endTransition()
                .transition(SEND_BACK, REVIEW, DRAFT).automatic().endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition();
        assertEquals(List.of(SEND_BACK), identifiers(graph.build().automaticFrom(REVIEW)));
    }

    @Test
    void aMixedCycleIsOnlyTheUnauthorizedCycleRule() {
        GraphBuilder<Doc, DocStatus, DocTransition> unallowed = docs()
                .transition(SUBMIT, DRAFT, REVIEW).automatic().endTransition()
                .transition(SEND_BACK, REVIEW, DRAFT).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition();
        assertEquals(List.of(DiagnosticCode.SG204.name()),
                problems(unallowed).stream().map(InvalidGraphException.Problem::code).toList());
        GraphBuilder<Doc, DocStatus, DocTransition> allowed = docs().allowsCycle(DRAFT)
                .transition(SUBMIT, DRAFT, REVIEW).automatic().endTransition()
                .transition(SEND_BACK, REVIEW, DRAFT).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition();
        assertEquals(List.of(SUBMIT), identifiers(allowed.build().automaticFrom(DRAFT)));
    }

    @Test
    void sg305ContinuingRuleIntoAStateWithoutAutomatic() {
        GraphBuilder<Doc, DocStatus, DocTransition> graph = docs()
                .transition(SUBMIT, DRAFT, REVIEW).action("submit", SUCCEEDS)
                        .recoverAndContinue(Declined.class, DISCARD).endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition();
        assertEquals(only(DiagnosticCode.SG305, "the failure route DISCARD of DRAFT -SUBMIT-> REVIEW continues the "
                + "chain, but ARCHIVED has no automatic transition"), problems(graph));
    }

    @Test
    void sg305ContinuingOnFailureIntoAStateWithoutAutomatic() {
        GraphBuilder<Doc, DocStatus, DocTransition> graph = docs()
                .transition(SUBMIT, DRAFT, REVIEW).action("submit", SUCCEEDS)
                        .onFailureAndContinue(DISCARD).endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition();
        assertEquals(only(DiagnosticCode.SG305, "the failure route DISCARD of DRAFT -SUBMIT-> REVIEW continues the "
                + "chain, but ARCHIVED has no automatic transition"), problems(graph));
    }

    @Test
    void aContinuingRouteIntoAnAutomaticStateIsFine() {
        GraphBuilder<Doc, DocStatus, DocTransition> graph = docs()
                .transition(SUBMIT, DRAFT, REVIEW).action("submit", SUCCEEDS)
                        .recoverAndContinue(Declined.class, DISCARD).endTransition()
                .transition(DISCARD, DRAFT, REVIEW).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).automatic().endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition();
        assertEquals(List.of(PUBLISH), identifiers(graph.build().automaticFrom(REVIEW)));
    }

    @Test
    void aRouteThatDoesNotContinueNeedsNoAutomaticEdgeAtItsDestination() {
        GraphBuilder<Doc, DocStatus, DocTransition> graph = docs()
                .transition(SUBMIT, DRAFT, REVIEW).action("submit", SUCCEEDS)
                        .recover(Declined.class, DISCARD).endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition();
        assertEquals(List.of(), graph.build().automaticFrom(DRAFT));
    }

    private static List<DocTransition> identifiers(List<TransitionSpec<Doc, DocStatus, DocTransition>> edges) {
        return edges.stream().map(TransitionSpec::transition).toList();
    }
}
