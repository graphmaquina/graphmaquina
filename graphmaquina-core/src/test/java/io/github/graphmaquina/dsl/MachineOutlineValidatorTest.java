package io.github.graphmaquina.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import io.github.graphmaquina.api.TransitionKind;
import io.github.graphmaquina.error.InvalidGraphException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The outline is the shared model: the DSL and the processor both normalise to it before validating. */
class MachineOutlineValidatorTest {

    static final List<String> STATES = List.of("DRAFT", "REVIEW", "PUBLISHED", "ARCHIVED");
    static final List<String> CATALOG = List.of("SUBMIT", "PUBLISH", "DISCARD", "SEND_BACK", "ARCHIVE", "RETOUCH");

    private static TransitionOutline move(String from, String transition, String to) {
        return new TransitionOutline(from, transition, to, TransitionKind.MOVE, Optional.empty());
    }

    private static TransitionOutline action(String from, String transition, String to, String onFailure) {
        return new TransitionOutline(from, transition, to, TransitionKind.ACTION, Optional.ofNullable(onFailure));
    }

    private static TransitionOutline recovering(String from, String transition, String to, TransitionKind kind,
            String onFailure, String... recoveries) {
        return new TransitionOutline(from, transition, to, kind, Optional.ofNullable(onFailure), List.of(recoveries),
                false, false, false, List.of());
    }

    private static List<PolicyOutline> policies(String... states) {
        List<PolicyOutline> policies = new ArrayList<>();
        for (String state : states) {
            policies.add(new PolicyOutline(state, state + " rejects the rest", Map.of()));
        }
        return policies;
    }

    private static List<String> codes(List<InvalidGraphException.Problem> problems) {
        List<String> codes = new ArrayList<>();
        for (InvalidGraphException.Problem problem : problems) {
            codes.add(problem.code());
        }
        return codes;
    }

    @Test
    void validOutlineHasNoProblems() {
        MachineOutline outline = new MachineOutline("Doc/DocStatus", STATES, CATALOG, "DRAFT", Set.of("ARCHIVED"),
                Set.of("REVIEW"),
                List.of(move("DRAFT", "SUBMIT", "REVIEW"), action("REVIEW", "PUBLISH", "PUBLISHED", null),
                        move("REVIEW", "SEND_BACK", "DRAFT"), move("PUBLISHED", "ARCHIVE", "ARCHIVED")),
                policies("DRAFT", "REVIEW", "PUBLISHED", "ARCHIVED"), List.of());
        assertEquals(List.of(), MachineOutlineValidator.validate(outline));
    }

    @Test
    void reportsStructuralCodesSortedByCodeAndState() {
        MachineOutline outline = new MachineOutline("Doc/DocStatus", STATES, CATALOG, "DRAFT",
                Set.of("ARCHIVED", "PUBLISHED"), Set.of("ARCHIVED"),
                List.of(move("DRAFT", "SUBMIT", "REVIEW"), action("REVIEW", "PUBLISH", "PUBLISHED", "ARCHIVE"),
                        move("PUBLISHED", "ARCHIVE", "ARCHIVED")),
                policies("DRAFT", "REVIEW", "PUBLISHED", "ARCHIVED"),
                List.of(new WatchOutline("ARCHIVED", List.of("SUBMIT"))));
        List<InvalidGraphException.Problem> problems = MachineOutlineValidator.validate(outline);
        // SG202: PUBLISHED is terminal but declares ARCHIVE; SG205: ARCHIVED is in no cycle;
        // SG301: no ARCHIVE leaves REVIEW; SG401: no SUBMIT leaves ARCHIVED; SG402: ARCHIVED is terminal and watches.
        assertEquals(List.of(DiagnosticCode.SG202.name(), DiagnosticCode.SG205.name(), DiagnosticCode.SG301.name(),
                DiagnosticCode.SG401.name(), DiagnosticCode.SG402.name()), codes(problems));
        assertTrue(problems.get(0).message().contains("PUBLISHED is terminal but declares [ARCHIVE]"));
    }

    @Test
    void unreachableAndUnallowedCycle() {
        MachineOutline outline = new MachineOutline("Doc/DocStatus", STATES, CATALOG, "DRAFT", Set.of("ARCHIVED"),
                Set.of(),
                List.of(move("DRAFT", "SUBMIT", "REVIEW"), move("REVIEW", "SEND_BACK", "DRAFT"),
                        move("PUBLISHED", "ARCHIVE", "ARCHIVED")),
                policies("DRAFT", "REVIEW", "PUBLISHED", "ARCHIVED"), List.of());
        List<InvalidGraphException.Problem> problems = MachineOutlineValidator.validate(outline);
        assertEquals(List.of(DiagnosticCode.SG203.name(), DiagnosticCode.SG203.name(), DiagnosticCode.SG204.name()),
                codes(problems));
        assertTrue(problems.get(0).message().startsWith("PUBLISHED is unreachable"));
        assertTrue(problems.get(2).message().contains("[DRAFT, REVIEW, DRAFT]"));
    }

    @Test
    void duplicateKeysAndBlankReasonsAreReportedOnTheOutlineToo() {
        MachineOutline outline = new MachineOutline("Doc/DocStatus", STATES, CATALOG, "DRAFT", Set.of("ARCHIVED"),
                Set.of("REVIEW"),
                List.of(move("DRAFT", "SUBMIT", "REVIEW"), move("DRAFT", "SUBMIT", "PUBLISHED"),
                        action("REVIEW", "PUBLISH", "PUBLISHED", null), move("REVIEW", "SEND_BACK", "DRAFT"),
                        move("PUBLISHED", "ARCHIVE", "ARCHIVED")),
                List.of(new PolicyOutline("DRAFT", " ", Map.of("PUBLISH", "not from a draft")),
                        new PolicyOutline("DRAFT", "twice", Map.of()),
                        new PolicyOutline("REVIEW", "in review", Map.of("PUBLISH", "already declared")),
                        new PolicyOutline("PUBLISHED", "published", Map.of()),
                        new PolicyOutline("ARCHIVED", "archived", Map.of())),
                List.of());
        assertEquals(List.of(DiagnosticCode.SG102.name(), DiagnosticCode.SG102.name(), DiagnosticCode.SG103.name(),
                DiagnosticCode.SG702.name()), codes(MachineOutlineValidator.validate(outline)));
    }

    /**
     * An element-level combination gets its own code, not a thrown record constructor: the processor normalises to
     * this model before it validates, so the model has to be able to hold what it is about to reject.
     */
    @Test
    void sg507OnFailureWithoutAnAction() {
        MachineOutline outline = new MachineOutline("Doc/DocStatus", STATES, CATALOG, "DRAFT", Set.of("ARCHIVED"),
                Set.of("REVIEW"),
                List.of(new TransitionOutline("DRAFT", "SUBMIT", "REVIEW", TransitionKind.MOVE, Optional.of("DISCARD")),
                        new TransitionOutline("DRAFT", "DISCARD", "ARCHIVED", TransitionKind.MOVE, Optional.empty()),
                        action("REVIEW", "PUBLISH", "PUBLISHED", null), move("REVIEW", "SEND_BACK", "DRAFT"),
                        move("PUBLISHED", "ARCHIVE", "ARCHIVED")),
                policies("DRAFT", "REVIEW", "PUBLISHED", "ARCHIVED"), List.of());
        List<InvalidGraphException.Problem> problems = MachineOutlineValidator.validate(outline);
        assertEquals(List.of(DiagnosticCode.SG507.name()), codes(problems));
        assertTrue(problems.get(0).message().contains("declares onFailure without an action"));
    }

    /**
     * A key is the pair (from, transition), not its printed form: {@code (A, X/Y)} and {@code (A/X, Y)} both print
     * {@code A/X/Y}, and must neither collide nor stand in for each other in any lookup.
     */
    @Test
    void namesHoldingASlashNeverMakeTwoKeysAlike() {
        List<String> states = List.of("A", "A/X", "END");
        List<String> catalog = List.of("X/Y", "Y", "GO");
        List<PolicyOutline> policies = List.of(new PolicyOutline("A", "a", Map.of()),
                new PolicyOutline("A/X", "ax", Map.of()), new PolicyOutline("END", "end", Map.of()));
        MachineOutline distinct = new MachineOutline("M", states, catalog, "A", Set.of("END"), Set.of(),
                List.of(move("A", "X/Y", "A/X"), move("A/X", "Y", "END")), policies, List.of());
        assertEquals(List.of(), MachineOutlineValidator.validate(distinct));

        MachineOutline watched = new MachineOutline("M", states, catalog, "A", Set.of("END"), Set.of(),
                List.of(move("A", "X/Y", "A/X"), move("A/X", "X/Y", "END")), policies,
                List.of(new WatchOutline("A/X", List.of("Y"))));
        assertEquals(List.of(DiagnosticCode.SG401.name()), codes(MachineOutlineValidator.validate(watched)));

        MachineOutline rejecting = new MachineOutline("M", states, catalog, "A", Set.of("END"), Set.of(),
                List.of(move("A", "GO", "A/X"), move("A/X", "Y", "END")),
                List.of(new PolicyOutline("A", "a", Map.of("X/Y", "not from A")),
                        new PolicyOutline("A/X", "ax", Map.of()), new PolicyOutline("END", "end", Map.of())),
                List.of());
        assertEquals(List.of(), MachineOutlineValidator.validate(rejecting));

        MachineOutline recovering = new MachineOutline("M", states, catalog, "A", Set.of("END"), Set.of(),
                List.of(action("A", "GO", "A/X", "X/Y"), move("A/X", "Y", "END")), policies, List.of());
        assertEquals(List.of(DiagnosticCode.SG301.name()), codes(MachineOutlineValidator.validate(recovering)));

        MachineOutline ruled = new MachineOutline("M", states, catalog, "A", Set.of("END"), Set.of(),
                List.of(recovering("A", "GO", "A/X", TransitionKind.ACTION, null, "X/Y"), move("A/X", "Y", "END")),
                policies, List.of());
        assertEquals(List.of(DiagnosticCode.SG301.name()), codes(MachineOutlineValidator.validate(ruled)));
    }

    /** A rule and the onFailure may name the same recovery transition, as long as an edge with it leaves the origin. */
    @Test
    void recoveryTransitionsThatLeaveTheOriginAreValid() {
        MachineOutline outline = new MachineOutline("Doc/DocStatus", STATES, CATALOG, "DRAFT", Set.of("ARCHIVED"),
                Set.of("REVIEW"),
                List.of(move("DRAFT", "SUBMIT", "REVIEW"),
                        recovering("REVIEW", "PUBLISH", "PUBLISHED", TransitionKind.ACTION, "SEND_BACK", "SEND_BACK"),
                        move("REVIEW", "SEND_BACK", "DRAFT"), move("PUBLISHED", "ARCHIVE", "ARCHIVED")),
                policies("DRAFT", "REVIEW", "PUBLISHED", "ARCHIVED"), List.of());
        assertEquals(List.of(), MachineOutlineValidator.validate(outline));
    }

    @Test
    void sg301ARecoveryTransitionThatIsTheEdgesOwnKey() {
        MachineOutline outline = new MachineOutline("Doc/DocStatus", STATES, CATALOG, "DRAFT", Set.of("ARCHIVED"),
                Set.of("REVIEW"),
                List.of(move("DRAFT", "SUBMIT", "REVIEW"),
                        recovering("REVIEW", "PUBLISH", "PUBLISHED", TransitionKind.ACTION, null, "PUBLISH"),
                        move("REVIEW", "SEND_BACK", "DRAFT"), move("PUBLISHED", "ARCHIVE", "ARCHIVED")),
                policies("DRAFT", "REVIEW", "PUBLISHED", "ARCHIVED"), List.of());
        assertEquals(List.of(new InvalidGraphException.Problem(DiagnosticCode.SG301.name(),
                        "REVIEW -PUBLISH-> PUBLISHED declares recovery PUBLISH, which is its own key")),
                MachineOutlineValidator.validate(outline));
    }

    /** Each origin of an identifier is checked on its own: SEND_BACK leaves REVIEW, but not DRAFT. */
    @Test
    void sg301ARecoveryTransitionMissingFromOneOfTwoOrigins() {
        MachineOutline outline = new MachineOutline("Doc/DocStatus", STATES, CATALOG, "DRAFT", Set.of("ARCHIVED"),
                Set.of("REVIEW"),
                List.of(move("DRAFT", "SUBMIT", "REVIEW"),
                        recovering("DRAFT", "PUBLISH", "PUBLISHED", TransitionKind.ACTION, null, "SEND_BACK"),
                        recovering("REVIEW", "PUBLISH", "PUBLISHED", TransitionKind.ACTION, null, "SEND_BACK"),
                        move("REVIEW", "SEND_BACK", "DRAFT"), move("PUBLISHED", "ARCHIVE", "ARCHIVED")),
                policies("DRAFT", "REVIEW", "PUBLISHED", "ARCHIVED"), List.of());
        assertEquals(List.of(new InvalidGraphException.Problem(DiagnosticCode.SG301.name(),
                        "DRAFT -PUBLISH-> PUBLISHED declares recovery SEND_BACK, but no transition SEND_BACK leaves"
                                + " DRAFT")),
                MachineOutlineValidator.validate(outline));
    }

    /** Like onFailure without an action: reported as SG507, and the identifiers are not checked any further. */
    @Test
    void sg507RecoveryTransitionsWithoutAnAction() {
        MachineOutline outline = new MachineOutline("Doc/DocStatus", STATES, CATALOG, "DRAFT", Set.of("ARCHIVED"),
                Set.of("REVIEW"),
                List.of(recovering("DRAFT", "SUBMIT", "REVIEW", TransitionKind.MOVE, null, "DISCARD"),
                        action("REVIEW", "PUBLISH", "PUBLISHED", null), move("REVIEW", "SEND_BACK", "DRAFT"),
                        move("PUBLISHED", "ARCHIVE", "ARCHIVED")),
                policies("DRAFT", "REVIEW", "PUBLISHED", "ARCHIVED"), List.of());
        assertEquals(List.of(new InvalidGraphException.Problem(DiagnosticCode.SG507.name(),
                        "DRAFT -SUBMIT-> REVIEW declares recover without an action")),
                MachineOutlineValidator.validate(outline));
    }

    /** Two rules that name the same recovery transition make one problem, not one per rule. */
    @Test
    void twoRulesSharingAMissingRecoveryTransitionAreReportedOnce() {
        MachineOutline outline = new MachineOutline("Doc/DocStatus", STATES, CATALOG, "DRAFT", Set.of("ARCHIVED"),
                Set.of("REVIEW"),
                List.of(move("DRAFT", "SUBMIT", "REVIEW"),
                        recovering("REVIEW", "PUBLISH", "PUBLISHED", TransitionKind.ACTION, null, "ARCHIVE", "ARCHIVE"),
                        move("REVIEW", "SEND_BACK", "DRAFT"), move("PUBLISHED", "ARCHIVE", "ARCHIVED")),
                policies("DRAFT", "REVIEW", "PUBLISHED", "ARCHIVED"), List.of());
        assertEquals(List.of(new InvalidGraphException.Problem(DiagnosticCode.SG301.name(),
                        "REVIEW -PUBLISH-> PUBLISHED declares recovery ARCHIVE, but no transition ARCHIVE leaves"
                                + " REVIEW")),
                MachineOutlineValidator.validate(outline));
    }

    /** A rule and the onFailure that miss the same identifier are checked apart: two problems, rule first. */
    @Test
    void sg301ARuleAndTheOnFailureThatMissTheSameRecoveryTransition() {
        MachineOutline outline = new MachineOutline("Doc/DocStatus", STATES, CATALOG, "DRAFT", Set.of("ARCHIVED"),
                Set.of("REVIEW"),
                List.of(move("DRAFT", "SUBMIT", "REVIEW"),
                        recovering("REVIEW", "PUBLISH", "PUBLISHED", TransitionKind.ACTION, "ARCHIVE", "ARCHIVE"),
                        move("REVIEW", "SEND_BACK", "DRAFT"), move("PUBLISHED", "ARCHIVE", "ARCHIVED")),
                policies("DRAFT", "REVIEW", "PUBLISHED", "ARCHIVED"), List.of());
        assertEquals(List.of(
                        new InvalidGraphException.Problem(DiagnosticCode.SG301.name(),
                                "REVIEW -PUBLISH-> PUBLISHED declares recovery"
                                + " ARCHIVE, but no transition ARCHIVE leaves REVIEW"),
                        new InvalidGraphException.Problem(DiagnosticCode.SG301.name(),
                                "REVIEW -PUBLISH-> PUBLISHED declares onFailure ="
                                + " ARCHIVE, but no transition ARCHIVE leaves REVIEW")),
                MachineOutlineValidator.validate(outline));
    }

    /** On one edge the rules come first, in declaration order, and the onFailure last. */
    @Test
    void theRecoveryTransitionsOfAnEdgeAreReportedBeforeItsOnFailure() {
        MachineOutline outline = new MachineOutline("Doc/DocStatus", STATES, CATALOG, "DRAFT", Set.of("ARCHIVED"),
                Set.of("REVIEW"),
                List.of(move("DRAFT", "SUBMIT", "REVIEW"),
                        recovering("REVIEW", "PUBLISH", "PUBLISHED", TransitionKind.ACTION, "RETOUCH", "DISCARD",
                                "ARCHIVE"),
                        move("REVIEW", "SEND_BACK", "DRAFT"), move("PUBLISHED", "ARCHIVE", "ARCHIVED")),
                policies("DRAFT", "REVIEW", "PUBLISHED", "ARCHIVED"), List.of());
        assertEquals(List.of(
                        new InvalidGraphException.Problem(DiagnosticCode.SG301.name(),
                                "REVIEW -PUBLISH-> PUBLISHED declares recovery"
                                + " DISCARD, but no transition DISCARD leaves REVIEW"),
                        new InvalidGraphException.Problem(DiagnosticCode.SG301.name(),
                                "REVIEW -PUBLISH-> PUBLISHED declares recovery"
                                + " ARCHIVE, but no transition ARCHIVE leaves REVIEW"),
                        new InvalidGraphException.Problem(DiagnosticCode.SG301.name(),
                                "REVIEW -PUBLISH-> PUBLISHED declares onFailure ="
                                + " RETOUCH, but no transition RETOUCH leaves REVIEW")),
                MachineOutlineValidator.validate(outline));
    }

    /** An edge keeps its own copy of the recovery transitions, in declaration order and with repeats. */
    @Test
    void anEdgeKeepsACopyOfItsRecoveryTransitions() {
        List<String> recoveries = new ArrayList<>(List.of("SEND_BACK", "DISCARD", "SEND_BACK"));
        TransitionOutline edge = new TransitionOutline("REVIEW", "PUBLISH", "PUBLISHED", TransitionKind.ACTION,
                Optional.empty(), recoveries, false, false, false, List.of());
        recoveries.clear();
        assertEquals(List.of("SEND_BACK", "DISCARD", "SEND_BACK"), edge.recoveryTransitions());
        assertThrows(UnsupportedOperationException.class, () -> edge.recoveryTransitions().add("ARCHIVE"));
        assertEquals(List.of(), action("REVIEW", "PUBLISH", "PUBLISHED", "SEND_BACK").recoveryTransitions());
        NullPointerException error = assertThrows(NullPointerException.class, () -> new TransitionOutline("REVIEW",
                "PUBLISH", "PUBLISHED", TransitionKind.ACTION, Optional.empty(), null, false, false, false, List.of()));
        assertTrue(error.getMessage().startsWith("recoveryTransitions"), error.getMessage());
    }

    @Test
    void outlineMustUseKnownStatesAndIdentifiers() {
        assertThrows(IllegalArgumentException.class, () -> new MachineOutline("X", STATES, CATALOG, "NOPE", Set.of(),
                Set.of(), List.of(), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new MachineOutline("X", STATES, CATALOG, "DRAFT", Set.of(),
                Set.of(), List.of(move("DRAFT", "NOPE", "REVIEW")), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new MachineOutline("X", STATES, CATALOG, "DRAFT", Set.of(),
                Set.of(), List.of(action("REVIEW", "PUBLISH", "PUBLISHED", "NOPE")), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new MachineOutline("X", STATES, CATALOG, "DRAFT", Set.of(),
                Set.of(), List.of(recovering("REVIEW", "PUBLISH", "PUBLISHED", TransitionKind.ACTION, null, "NOPE")),
                List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new MachineOutline("X", STATES, CATALOG, "DRAFT", Set.of(),
                Set.of(), List.of(), List.of(), List.of(new WatchOutline("DRAFT", List.of("NOPE")))));
    }

    /** V states and V x degree declared edges: the outline holds exactly those, and validation accepts them. */
    @Test
    void aLargeOutlineHoldsExactlyItsDeclaredEdges() {
        MachineOutline outline = CirculantOutlines.of(500, 8, true);
        assertEquals(500 * 8, outline.transitions().size());
        assertEquals(8, outline.catalog().size());
        assertEquals(8, outline.outgoing("s0").size());
        assertTrue(MachineOutlineValidator.validate(outline).isEmpty());
    }

    /**
     * The same graph without its cycle authorisations: one witness per cyclic component, not one message per
     * cycle. 500 states each leaving through eight identifiers hold far more cycles than could be listed.
     */
    @Test
    void anUnauthorizedLargeCycleReportsOneWitnessPerComponent() {
        List<InvalidGraphException.Problem> problems =
                MachineOutlineValidator.validate(CirculantOutlines.of(500, 8, false));
        assertEquals(List.of(DiagnosticCode.SG204.name()), codes(problems));
        assertTrue(problems.get(0).message().startsWith("unauthorized cycle "));
    }

    private static TransitionOutline automatic(String from, String transition, String to, boolean guarded) {
        return new TransitionOutline(from, transition, to, TransitionKind.MOVE, Optional.empty(), List.of(), guarded,
                true, false, List.of());
    }

    private static MachineOutline withAutomatic(List<TransitionOutline> edges, List<AutomaticOrderOutline> orders) {
        return new MachineOutline("Doc/DocStatus", STATES, CATALOG, "DRAFT", Set.of("ARCHIVED"), Set.of(), edges,
                policies("DRAFT", "REVIEW", "PUBLISHED", "ARCHIVED"), List.of(), List.of(), orders);
    }

    @Test
    void theAutomaticRulesWaitForTheOtherStructuralChecks() {
        MachineOutline outline = withAutomatic(
                List.of(automatic("DRAFT", "SUBMIT", "REVIEW", false), automatic("DRAFT", "DISCARD", "ARCHIVED", false),
                        action("REVIEW", "PUBLISH", "PUBLISHED", "RETOUCH"), move("PUBLISHED", "ARCHIVE", "ARCHIVED")),
                List.of());
        assertEquals(List.of(DiagnosticCode.SG301.name()), codes(MachineOutlineValidator.validate(outline)));
    }

    @Test
    void anOrderedPairBuildsWithoutProblems() {
        MachineOutline outline = withAutomatic(
                List.of(automatic("DRAFT", "SUBMIT", "REVIEW", true), automatic("DRAFT", "DISCARD", "ARCHIVED", false),
                        move("REVIEW", "PUBLISH", "PUBLISHED"), move("PUBLISHED", "ARCHIVE", "ARCHIVED")),
                List.of(new AutomaticOrderOutline("DRAFT", List.of("SUBMIT", "DISCARD"))));
        assertEquals(List.of(), MachineOutlineValidator.validate(outline));
    }

    @Test
    void aContinuationFlagWithoutAnOnFailureIsLeftToTheProducer() {
        TransitionOutline flagged = new TransitionOutline("DRAFT", "SUBMIT", "REVIEW", TransitionKind.ACTION,
                Optional.empty(), List.of(), false, false, true, List.of());
        MachineOutline outline = withAutomatic(List.of(flagged, move("REVIEW", "PUBLISH", "PUBLISHED"),
                move("PUBLISHED", "ARCHIVE", "ARCHIVED")), List.of());
        assertEquals(List.of(), MachineOutlineValidator.validate(outline));
    }

    @Test
    void aRecoveryNamedByTwoRulesIsReportedOnceWhenOneOfThemContinues() {
        TransitionOutline twice = new TransitionOutline("DRAFT", "SUBMIT", "REVIEW", TransitionKind.ACTION,
                Optional.empty(), List.of("DISCARD", "DISCARD"), false, false, false, List.of("DISCARD"));
        MachineOutline outline = withAutomatic(List.of(twice, move("DRAFT", "DISCARD", "ARCHIVED"),
                move("REVIEW", "PUBLISH", "PUBLISHED"), move("PUBLISHED", "ARCHIVE", "ARCHIVED")), List.of());
        assertEquals(List.of(DiagnosticCode.SG305.name()), codes(MachineOutlineValidator.validate(outline)));
    }
}
