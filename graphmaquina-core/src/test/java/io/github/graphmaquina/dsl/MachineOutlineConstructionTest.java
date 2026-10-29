package io.github.graphmaquina.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.TransitionKind;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Characterizes the exact {@link IllegalArgumentException} messages, null handling and check order of
 * {@link MachineOutline}'s compact constructor, which looks references up in local {@link Set}s instead of
 * {@link List#contains}. These tests exist to prove that swap does not alter any observable outcome, and they pin
 * where in that order the recovery transitions of an edge are checked.
 */
class MachineOutlineConstructionTest {

    private static final String NAME = "M";
    private static final List<String> STATES = List.of("A", "B");
    private static final List<String> CATALOG = List.of("Z", "X");

    private static TransitionOutline edge(String from, String transition, String to) {
        return new TransitionOutline(from, transition, to, TransitionKind.MOVE, Optional.empty());
    }

    private static TransitionOutline edgeWithFailure(String from, String transition, String to, String onFailure) {
        return new TransitionOutline(from, transition, to, TransitionKind.ACTION, Optional.of(onFailure));
    }

    private static TransitionOutline edgeWithRecoveries(String from, String transition, String to,
            String... recoveries) {
        return new TransitionOutline(from, transition, to, TransitionKind.ACTION, Optional.empty(),
                List.of(recoveries), false, false, false, List.of());
    }

    private static void assertRejected(String expectedMessage, Executable call) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, call);
        assertEquals(expectedMessage, error.getMessage());
    }

    // --- structural checks: emptiness and duplicates -----------------------------------------------------------

    @Test
    void emptyStatesAreRejected() {
        assertRejected("M: states must be non-empty and distinct", () -> new MachineOutline(NAME, List.of(), List.of(),
                "A", Set.of(), Set.of(), List.of(), List.of(), List.of()));
    }

    @Test
    void duplicateStatesAreRejected() {
        assertRejected("M: states must be non-empty and distinct", () -> new MachineOutline(NAME, List.of("A", "A"),
                List.of(), "A", Set.of(), Set.of(), List.of(), List.of(), List.of()));
    }

    @Test
    void duplicateCatalogIsRejected() {
        assertRejected("M: transition identifiers must be distinct", () -> new MachineOutline(NAME, STATES,
                List.of("X", "X"), "A", Set.of(), Set.of(), List.of(), List.of(), List.of()));
    }

    // --- unknown references, each isolated so no other check can pre-empt it -----------------------------------

    @Test
    void unknownInitialIsRejected() {
        assertRejected("M: initial NOPE is not one of [A, B]", () -> new MachineOutline(NAME, STATES, CATALOG,
                "NOPE", Set.of(), Set.of(), List.of(), List.of(), List.of()));
    }

    @Test
    void unknownTerminalIsRejected() {
        assertRejected("M: terminal NOPE is not one of [A, B]", () -> new MachineOutline(NAME, STATES, CATALOG, "A",
                Set.of("NOPE"), Set.of(), List.of(), List.of(), List.of()));
    }

    @Test
    void unknownCycleAllowedIsRejected() {
        assertRejected("M: allowsCycle NOPE is not one of [A, B]", () -> new MachineOutline(NAME, STATES, CATALOG,
                "A", Set.of(), Set.of("NOPE"), List.of(), List.of(), List.of()));
    }

    @Test
    void unknownTransitionFromIsRejected() {
        assertRejected("M: transition.from NOPE is not one of [A, B]", () -> new MachineOutline(NAME, STATES,
                CATALOG, "A", Set.of(), Set.of(), List.of(edge("NOPE", "Z", "A")), List.of(), List.of()));
    }

    @Test
    void unknownTransitionToIsRejected() {
        assertRejected("M: transition.to NOPE is not one of [A, B]", () -> new MachineOutline(NAME, STATES, CATALOG,
                "A", Set.of(), Set.of(), List.of(edge("A", "Z", "NOPE")), List.of(), List.of()));
    }

    @Test
    void unknownOnFailureIsRejected() {
        assertRejected("M: transition.onFailure NOPE is not one of [Z, X]", () -> new MachineOutline(NAME, STATES,
                CATALOG, "A", Set.of(), Set.of(), List.of(edgeWithFailure("A", "Z", "B", "NOPE")), List.of(),
                List.of()));
    }

    @Test
    void unknownRecoveryTransitionIsRejected() {
        assertRejected("M: transition.recovery NOPE is not one of [Z, X]", () -> new MachineOutline(NAME, STATES,
                CATALOG, "A", Set.of(), Set.of(), List.of(edgeWithRecoveries("A", "Z", "B", "X", "NOPE")), List.of(),
                List.of()));
    }

    @Test
    void unknownContinuingRecoveryIsRejected() {
        TransitionOutline edge = new TransitionOutline("A", "Z", "B", TransitionKind.ACTION, Optional.empty(),
                List.of("X"), false, false, false, List.of("NOPE"));
        assertRejected("M: transition.continuingRecovery NOPE is not one of [Z, X]", () -> new MachineOutline(NAME,
                STATES, CATALOG, "A", Set.of(), Set.of(), List.of(edge), List.of(), List.of()));
    }

    @Test
    void unknownAutomaticOrderStateIsRejected() {
        assertRejected("M: automaticOrder.state NOPE is not one of [A, B]", () -> new MachineOutline(NAME, STATES,
                CATALOG, "A", Set.of(), Set.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new AutomaticOrderOutline("NOPE", List.of("Z")))));
    }

    @Test
    void unknownAutomaticOrderTransitionIsRejected() {
        assertRejected("M: automaticOrder.transition NOPE is not one of [Z, X]", () -> new MachineOutline(NAME, STATES,
                CATALOG, "A", Set.of(), Set.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new AutomaticOrderOutline("A", List.of("Z", "NOPE")))));
    }

    @Test
    void unknownPolicyStateIsRejected() {
        assertRejected("M: policy.state NOPE is not one of [A, B]", () -> new MachineOutline(NAME, STATES, CATALOG,
                "A", Set.of(), Set.of(), List.of(), List.of(new PolicyOutline("NOPE", "why", Map.of())), List.of()));
    }

    @Test
    void unknownPolicyRejectionIsRejected() {
        assertRejected("M: policy.reject NOPE is not one of [Z, X]", () -> new MachineOutline(NAME, STATES, CATALOG,
                "A", Set.of(), Set.of(), List.of(),
                List.of(new PolicyOutline("A", "why", Map.of("NOPE", "reason"))), List.of()));
    }

    @Test
    void unknownWatchStateIsRejected() {
        assertRejected("M: watch.state NOPE is not one of [A, B]", () -> new MachineOutline(NAME, STATES, CATALOG,
                "A", Set.of(), Set.of(), List.of(), List.of(), List.of(new WatchOutline("NOPE", List.of()))));
    }

    @Test
    void unknownWatchTransitionIsRejected() {
        assertRejected("M: watch.transition NOPE is not one of [Z, X]", () -> new MachineOutline(NAME, STATES,
                CATALOG, "A", Set.of(), Set.of(), List.of(), List.of(),
                List.of(new WatchOutline("A", List.of("NOPE")))));
    }

    /** The message renders the original declaration-order {@code catalog} list, never the lookup set's order. */
    @Test
    void reportsOriginalCatalogOrderOnUnknownTransition() {
        TransitionOutline edge = new TransitionOutline("A", "MISSING", "B", TransitionKind.MOVE, Optional.empty());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> new MachineOutline(
                "M", List.of("A", "B"), List.of("Z", "X"), "A", Set.of("B"), Set.of(),
                List.of(edge), List.of(), List.of()));
        assertEquals("M: transition.id MISSING is not one of [Z, X]", error.getMessage());
    }

    // --- check order: when several defects coexist, the earliest one in source order is reported ---------------
    // This chains every membership check, adjacent pair by adjacent pair, in the constructor's exact order:
    // states -> catalog -> initial -> terminal -> allowsCycle -> (from -> to -> id -> onFailure -> recovery) per
    // edge -> (policy.state -> policy.reject) per policy -> (watch.state -> watch.transition) per watch.

    @Test
    void emptyStatesWinsOverDuplicateCatalog() {
        assertRejected("M: states must be non-empty and distinct", () -> new MachineOutline(NAME, List.of(),
                List.of("X", "X"), "whatever", Set.of(), Set.of(), List.of(), List.of(), List.of()));
    }

    @Test
    void duplicateCatalogWinsOverUnknownInitial() {
        assertRejected("M: transition identifiers must be distinct", () -> new MachineOutline(NAME, STATES,
                List.of("X", "X"), "NOPE", Set.of(), Set.of(), List.of(), List.of(), List.of()));
    }

    @Test
    void unknownInitialWinsOverUnknownTerminal() {
        assertRejected("M: initial NOPE is not one of [A, B]", () -> new MachineOutline(NAME, STATES, CATALOG,
                "NOPE", Set.of("ALSO_NOPE"), Set.of(), List.of(), List.of(), List.of()));
    }

    @Test
    void unknownTerminalWinsOverUnknownCycleAllowed() {
        assertRejected("M: terminal NOPE is not one of [A, B]", () -> new MachineOutline(NAME, STATES, CATALOG, "A",
                Set.of("NOPE"), Set.of("ALSO_NOPE"), List.of(), List.of(), List.of()));
    }

    @Test
    void unknownCycleAllowedWinsOverUnknownTransitionFrom() {
        assertRejected("M: allowsCycle NOPE is not one of [A, B]", () -> new MachineOutline(NAME, STATES, CATALOG,
                "A", Set.of(), Set.of("NOPE"), List.of(edge("ALSO_NOPE", "Z", "A")), List.of(), List.of()));
    }

    @Test
    void transitionFromWinsOverTransitionTo() {
        assertRejected("M: transition.from NOPE1 is not one of [A, B]", () -> new MachineOutline(NAME, STATES,
                CATALOG, "A", Set.of(), Set.of(), List.of(edge("NOPE1", "Z", "NOPE2")), List.of(), List.of()));
    }

    @Test
    void transitionToWinsOverTransitionId() {
        assertRejected("M: transition.to NOPE_TO is not one of [A, B]", () -> new MachineOutline(NAME, STATES,
                CATALOG, "A", Set.of(), Set.of(), List.of(edge("A", "NOPE_ID", "NOPE_TO")), List.of(), List.of()));
    }

    @Test
    void transitionIdWinsOverOnFailure() {
        assertRejected("M: transition.id NOPE_ID is not one of [Z, X]", () -> new MachineOutline(NAME, STATES,
                CATALOG, "A", Set.of(), Set.of(), List.of(edgeWithFailure("A", "NOPE_ID", "B", "NOPE_FAIL")),
                List.of(), List.of()));
    }

    @Test
    void onFailureWinsOverPolicyState() {
        assertRejected("M: transition.onFailure NOPE_FAIL is not one of [Z, X]", () -> new MachineOutline(NAME,
                STATES, CATALOG, "A", Set.of(), Set.of(), List.of(edgeWithFailure("A", "Z", "B", "NOPE_FAIL")),
                List.of(new PolicyOutline("NOPE_POLICY_STATE", "why", Map.of())), List.of()));
    }

    @Test
    void onFailureWinsOverRecovery() {
        assertRejected("M: transition.onFailure NOPE_FAIL is not one of [Z, X]", () -> new MachineOutline(NAME,
                STATES, CATALOG, "A", Set.of(), Set.of(),
                List.of(new TransitionOutline("A", "Z", "B", TransitionKind.ACTION, Optional.of("NOPE_FAIL"),
                        List.of("NOPE_RECOVERY"), false, false, false, List.of())),
                List.of(), List.of()));
    }

    @Test
    void recoveryWinsOverPolicyState() {
        assertRejected("M: transition.recovery NOPE_RECOVERY is not one of [Z, X]", () -> new MachineOutline(NAME,
                STATES, CATALOG, "A", Set.of(), Set.of(), List.of(edgeWithRecoveries("A", "Z", "B", "NOPE_RECOVERY")),
                List.of(new PolicyOutline("NOPE_POLICY_STATE", "why", Map.of())), List.of()));
    }

    @Test
    void policyStateWinsOverPolicyRejection() {
        assertRejected("M: policy.state NOPE_STATE is not one of [A, B]", () -> new MachineOutline(NAME, STATES,
                CATALOG, "A", Set.of(), Set.of(), List.of(),
                List.of(new PolicyOutline("NOPE_STATE", "why", Map.of("NOPE_REJECT", "reason"))), List.of()));
    }

    @Test
    void policyRejectionWinsOverWatchState() {
        assertRejected("M: policy.reject NOPE_REJECT is not one of [Z, X]", () -> new MachineOutline(NAME, STATES,
                CATALOG, "A", Set.of(), Set.of(), List.of(),
                List.of(new PolicyOutline("A", "why", Map.of("NOPE_REJECT", "reason"))),
                List.of(new WatchOutline("NOPE_WATCH_STATE", List.of()))));
    }

    @Test
    void watchStateWinsOverWatchTransition() {
        assertRejected("M: watch.state NOPE_STATE is not one of [A, B]", () -> new MachineOutline(NAME, STATES,
                CATALOG, "A", Set.of(), Set.of(), List.of(), List.of(),
                List.of(new WatchOutline("NOPE_STATE", List.of("NOPE_TRANSITION")))));
    }

    // --- nulls: every record component is @NonNull; each is checked in declaration order -----------------------

    static Stream<Arguments> nullComponents() {
        return Stream.of(
                Arguments.of("name", (Executable) () -> new MachineOutline(null, STATES, CATALOG, "A", Set.of("B"),
                        Set.of(), List.of(), List.of(), List.of())),
                Arguments.of("states", (Executable) () -> new MachineOutline(NAME, null, CATALOG, "A", Set.of("B"),
                        Set.of(), List.of(), List.of(), List.of())),
                Arguments.of("catalog", (Executable) () -> new MachineOutline(NAME, STATES, null, "A", Set.of("B"),
                        Set.of(), List.of(), List.of(), List.of())),
                Arguments.of("initial", (Executable) () -> new MachineOutline(NAME, STATES, CATALOG, null,
                        Set.of("B"), Set.of(), List.of(), List.of(), List.of())),
                Arguments.of("terminals", (Executable) () -> new MachineOutline(NAME, STATES, CATALOG, "A", null,
                        Set.of(), List.of(), List.of(), List.of())),
                Arguments.of("cycleAllowed", (Executable) () -> new MachineOutline(NAME, STATES, CATALOG, "A",
                        Set.of("B"), null, List.of(), List.of(), List.of())),
                Arguments.of("transitions", (Executable) () -> new MachineOutline(NAME, STATES, CATALOG, "A",
                        Set.of("B"), Set.of(), null, List.of(), List.of())),
                Arguments.of("policies", (Executable) () -> new MachineOutline(NAME, STATES, CATALOG, "A",
                        Set.of("B"), Set.of(), List.of(), null, List.of())),
                Arguments.of("watches", (Executable) () -> new MachineOutline(NAME, STATES, CATALOG, "A",
                        Set.of("B"), Set.of(), List.of(), List.of(), null)));
    }

    @ParameterizedTest
    @MethodSource("nullComponents")
    void rejectsNullComponentWithParameterName(String parameter, Executable call) {
        NullPointerException error = assertThrows(NullPointerException.class, call);
        assertTrue(error.getMessage().startsWith(parameter), error.getMessage());
    }

    @Test
    void outgoingRefusesANullState() {
        MachineOutline outline = new MachineOutline(NAME, STATES, CATALOG, "A", Set.of("B"), Set.of(),
                List.of(edge("A", "Z", "B")), List.of(), List.of());
        NullPointerException error = assertThrows(NullPointerException.class, () -> outline.outgoing(null));
        assertTrue(error.getMessage().startsWith("state"), error.getMessage());
    }
}
