package io.github.graphmaquina.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The sub-machine starts an outcome carries: none by default, a copy of the given list in an applied or failed outcome,
 * and none from the constructors that take no list. A start refuses a null component.
 */
class OutcomeStartsTest {

    private static final MachineKey<String, DayOfWeek, Month> CHILD =
            MachineKey.of(String.class, DayOfWeek.class, Month.class);
    private static final MachineKey<Integer, Month, DayOfWeek> PARENT =
            MachineKey.of(Integer.class, Month.class, DayOfWeek.class);
    private static final SubmachineStart<String, DayOfWeek, Month> START = new SubmachineStart<>(CHILD, "child",
            DayOfWeek.FRIDAY, DayOfWeek.MONDAY, PARENT, 7, Month.MARCH, 1);

    @Test
    void rejectedAndIgnoredOutcomesStartNothing() {
        assertEquals(List.of(), new Outcome.Rejected<String, DayOfWeek, Month>("e", DayOfWeek.MONDAY, Month.JANUARY,
                "no", RejectionKind.NO_TRANSITION, Optional.empty()).starts());
        assertEquals(List.of(), new Outcome.Ignored<String, DayOfWeek, Month>("e", DayOfWeek.MONDAY,
                IgnoreReason.NO_WATCH).starts());
    }

    @Test
    void appliedAndFailedOutcomesKeepACopyOfTheirStarts() {
        List<SubmachineStart<?, ?, ?>> starts = new ArrayList<>(List.of(START));
        Outcome.Applied<String, DayOfWeek, Month> applied = new Outcome.Applied<>("e", DayOfWeek.MONDAY,
                Month.JANUARY, DayOfWeek.TUESDAY, List.of(), List.of(), starts);
        Outcome.Failed<String, DayOfWeek, Month> failed = new Outcome.Failed<>("e", DayOfWeek.MONDAY, Month.JANUARY,
                DayOfWeek.TUESDAY, Month.MARCH, DayOfWeek.FRIDAY, "declined", Optional.empty(), List.of(), List.of(),
                Optional.empty(), starts);
        starts.clear();
        assertEquals(List.of(START), applied.starts());
        assertEquals(List.of(START), failed.starts());
        assertThrows(UnsupportedOperationException.class, () -> applied.starts().clear());
        assertThrows(UnsupportedOperationException.class, () -> failed.starts().clear());
    }

    @Test
    void theConstructorsWithoutStartsReportNone() {
        assertEquals(List.of(), new Outcome.Applied<String, DayOfWeek, Month>("e", DayOfWeek.MONDAY, Month.JANUARY,
                DayOfWeek.TUESDAY, List.of(), List.of()).starts());
        assertEquals(List.of(), new Outcome.Failed<String, DayOfWeek, Month>("e", DayOfWeek.MONDAY, Month.JANUARY,
                DayOfWeek.TUESDAY, Month.MARCH, DayOfWeek.FRIDAY, "declined", Optional.empty(), List.of(),
                List.of()).starts());
        assertEquals(List.of(), new Outcome.Failed<String, DayOfWeek, Month>("e", DayOfWeek.MONDAY, Month.JANUARY,
                DayOfWeek.TUESDAY, Month.MARCH, DayOfWeek.FRIDAY, "declined", Optional.empty(), List.of(), List.of(),
                Optional.empty()).starts());
    }

    @Test
    void nullStartsAndNullStartComponentsAreRefused() {
        NullPointerException starts = assertThrows(NullPointerException.class,
                () -> new Outcome.Applied<String, DayOfWeek, Month>("e", DayOfWeek.MONDAY, Month.JANUARY,
                        DayOfWeek.TUESDAY, List.of(), List.of(), null));
        assertTrue(starts.getMessage().startsWith("starts"), starts.getMessage());
        NullPointerException previous = assertThrows(NullPointerException.class,
                () -> new SubmachineStart<String, DayOfWeek, Month>(CHILD, "child", null, DayOfWeek.MONDAY, PARENT, 7,
                        Month.MARCH, 1));
        assertTrue(previous.getMessage().startsWith("previous"), previous.getMessage());
        NullPointerException parent = assertThrows(NullPointerException.class,
                () -> new SubmachineStart<String, DayOfWeek, Month>(CHILD, "child", DayOfWeek.FRIDAY,
                        DayOfWeek.MONDAY, PARENT, null, Month.MARCH, 1));
        assertTrue(parent.getMessage().startsWith("parent"), parent.getMessage());
    }
}
