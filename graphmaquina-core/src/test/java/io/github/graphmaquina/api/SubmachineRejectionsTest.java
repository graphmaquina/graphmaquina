package io.github.graphmaquina.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.DayOfWeek;
import java.time.Month;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The two rejection kinds a sub-machine adds, as values: their order in both enums, the code each maps to, the
 * explanation they give, and the guard they never carry.
 */
class SubmachineRejectionsTest {

    private static Outcome.Rejected<String, DayOfWeek, Month> rejected(RejectionKind kind, String reason) {
        return new Outcome.Rejected<>("e", DayOfWeek.MONDAY, Month.JANUARY, reason, kind, Optional.empty());
    }

    /** The names are stable identifiers: the new constants follow the existing ones. */
    @Test
    void theNewKindsAndCodesFollowTheExistingOnes() {
        assertEquals(List.of(RejectionKind.NO_TRANSITION, RejectionKind.GUARD, RejectionKind.DETACHED,
                RejectionKind.RESERVED_EXIT), List.of(RejectionKind.values()));
        assertEquals(List.of(OutcomeCode.APPLIED, OutcomeCode.NO_TRANSITION, OutcomeCode.GUARD_REJECTED,
                        OutcomeCode.DETACHED, OutcomeCode.RESERVED_EXIT, OutcomeCode.FAILURE_ROUTED,
                        OutcomeCode.NO_WATCH, OutcomeCode.NO_RULE_MATCHED),
                List.of(OutcomeCode.values()));
        assertEquals(OutcomeStatus.REJECTED, OutcomeCode.DETACHED.status());
        assertEquals(OutcomeStatus.REJECTED, OutcomeCode.RESERVED_EXIT.status());
    }

    @Test
    void aDetachedRejectionReportsItsCodeAndReason() {
        Outcome.Rejected<String, DayOfWeek, Month> detached =
                rejected(RejectionKind.DETACHED, "the parent is not in MONDAY");
        assertEquals(OutcomeCode.DETACHED, detached.code());
        assertEquals(OutcomeStatus.REJECTED, detached.status());
        assertEquals(Optional.of("the parent is not in MONDAY"), detached.explanation());
        assertEquals(Optional.empty(), detached.guard());
    }

    @Test
    void aReservedExitRejectionReportsItsCodeAndReason() {
        Outcome.Rejected<String, DayOfWeek, Month> reserved =
                rejected(RejectionKind.RESERVED_EXIT, "JANUARY leaves MONDAY only when its sub-machine ends");
        assertEquals(OutcomeCode.RESERVED_EXIT, reserved.code());
        assertEquals(OutcomeStatus.REJECTED, reserved.status());
        assertEquals(Optional.of("JANUARY leaves MONDAY only when its sub-machine ends"), reserved.explanation());
    }

    /** Only a guard rejection names a guard: every other kind refuses one, with its own name in the message. */
    @Test
    void everyKindButGuardRefusesAGuard() {
        for (RejectionKind kind : List.of(RejectionKind.NO_TRANSITION, RejectionKind.DETACHED,
                RejectionKind.RESERVED_EXIT)) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> new Outcome.Rejected<String, DayOfWeek, Month>("e", DayOfWeek.MONDAY, Month.JANUARY, "no",
                            kind, Optional.of("G")));
            assertEquals("a " + kind + " rejection carries no guard", error.getMessage());
        }
    }
}
