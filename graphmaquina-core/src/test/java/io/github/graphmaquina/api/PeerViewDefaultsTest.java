package io.github.graphmaquina.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.Month;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PeerViewDefaultsTest {

    private static PeerView<String, DayOfWeek, Month> view(Map<String, DayOfWeek> states) {
        return new PeerView<>() {
            @Override public MachineKey<String, DayOfWeek, Month> key() {
                return MachineKey.of(String.class, DayOfWeek.class, Month.class);
            }

            @Override public DayOfWeek stateOf(String peer) { return states.get(peer); }
        };
    }

    @Test
    void allInIsFalseOnEmptyAndTrueOnlyWhenAllMatch() {
        PeerView<String, DayOfWeek, Month> view =
                view(Map.of("a", DayOfWeek.MONDAY, "b", DayOfWeek.MONDAY, "c", DayOfWeek.FRIDAY));
        assertFalse(view.allIn(List.of(), DayOfWeek.MONDAY));
        assertTrue(view.allIn(List.of("a", "b"), DayOfWeek.MONDAY));
        assertFalse(view.allIn(List.of("a", "c"), DayOfWeek.MONDAY));
    }

    @Test
    void anyInAndNoneIn() {
        PeerView<String, DayOfWeek, Month> view = view(Map.of("a", DayOfWeek.MONDAY, "c", DayOfWeek.FRIDAY));
        assertTrue(view.anyIn(List.of("a", "c"), DayOfWeek.FRIDAY));
        assertFalse(view.anyIn(List.of("a"), DayOfWeek.FRIDAY));
        assertTrue(view.noneIn(List.of(), DayOfWeek.FRIDAY));
        assertTrue(view.noneIn(List.of("a"), DayOfWeek.FRIDAY));
        assertFalse(view.noneIn(List.of("a", "c"), DayOfWeek.FRIDAY));
    }

    @Test
    void conditionsDelegateToTheView() {
        PeerView<String, DayOfWeek, Month> view = view(Map.of("a", DayOfWeek.MONDAY));
        NamedCondition<Object, String, DayOfWeek, Month> all = WatchCondition.allIn(DayOfWeek.MONDAY);
        assertTrue(all.condition().test(new Object(), List.of("a"), view));
        NamedCondition<Object, String, DayOfWeek, Month> none = WatchCondition.noneIn(DayOfWeek.MONDAY);
        assertFalse(none.condition().test(new Object(), List.of("a"), view));
    }
}
