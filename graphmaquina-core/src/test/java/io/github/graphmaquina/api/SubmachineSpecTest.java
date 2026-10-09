package io.github.graphmaquina.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.dsl.StateGraph;
import java.time.DayOfWeek;
import java.time.Month;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * What a {@link SubmachineSpec} keeps: its components as given, and a read-only copy of its exits in the given order.
 * It refuses a null component, and a null terminal or identifier among the exits, by name.
 */
class SubmachineSpecTest {

    /** The child entity of these tests. */
    static final class Lamp { }

    /** States of the child. */
    enum Light { ON, OFF, BROKEN }

    /** Identifiers of the child. */
    enum Flip { SWITCH_OFF, BREAK }

    private static final MachineKey<Lamp, Light, Flip> LAMPS = MachineKey.of(Lamp.class, Light.class, Flip.class);

    private static final GraphDefinition<Lamp, Light, Flip> CHILD = StateGraph.define(LAMPS)
            .initial(Light.ON).terminal(Light.OFF).terminal(Light.BROKEN)
            .statePolicy(Light.ON, "a lamp that is on only switches off or breaks")
            .transition(Flip.SWITCH_OFF, Light.ON, Light.OFF).endTransition()
            .transition(Flip.BREAK, Light.ON, Light.BROKEN).endTransition()
            .build();

    private static final Function<String, Optional<Lamp>> CHILD_OF = (String parent) -> Optional.empty();

    private static final Function<Lamp, Optional<String>> PARENT_OF = (Lamp lamp) -> Optional.empty();

    @Test
    void aSpecKeepsItsComponentsAndAReadOnlyCopyOfItsExitsInTheGivenOrder() {
        Map<Light, Month> exits = new LinkedHashMap<>();
        exits.put(Light.BROKEN, Month.MARCH);
        exits.put(Light.OFF, Month.JANUARY);
        SubmachineSpec<String, DayOfWeek, Month, Lamp, Light, Flip> spec =
                new SubmachineSpec<>(DayOfWeek.MONDAY, CHILD, CHILD_OF, PARENT_OF, exits);
        exits.put(Light.ON, Month.MAY);
        assertEquals(DayOfWeek.MONDAY, spec.state());
        assertSame(CHILD, spec.child());
        assertSame(CHILD_OF, spec.childOf());
        assertSame(PARENT_OF, spec.parentOf());
        assertEquals(List.of(Light.BROKEN, Light.OFF), List.copyOf(spec.exits().keySet()));
        assertEquals(List.of(Month.MARCH, Month.JANUARY), List.copyOf(spec.exits().values()));
        assertThrows(UnsupportedOperationException.class, () -> spec.exits().put(Light.ON, Month.MAY));
        assertEquals(LAMPS, spec.childKey());
    }

    @Test
    void aNullTerminalOrIdentifierAmongTheExitsIsRefusedByName() {
        Map<Light, Month> nullTerminal = new HashMap<>();
        nullTerminal.put(null, Month.JANUARY);
        NullPointerException terminal = assertThrows(NullPointerException.class,
                () -> new SubmachineSpec<>(DayOfWeek.MONDAY, CHILD, CHILD_OF, PARENT_OF, nullTerminal));
        assertEquals("exit terminal", terminal.getMessage());
        Map<Light, Month> nullTransition = new HashMap<>();
        nullTransition.put(Light.OFF, null);
        NullPointerException transition = assertThrows(NullPointerException.class,
                () -> new SubmachineSpec<>(DayOfWeek.MONDAY, CHILD, CHILD_OF, PARENT_OF, nullTransition));
        assertEquals("exit transition", transition.getMessage());
    }

    @Test
    void aNullComponentIsRefusedByName() {
        Map<Light, Month> exits = Map.of(Light.OFF, Month.JANUARY);
        List<String> names = List.of("state", "child", "childOf", "parentOf", "exits");
        List<Runnable> calls = List.of(
                () -> new SubmachineSpec<String, DayOfWeek, Month, Lamp, Light, Flip>(null, CHILD, CHILD_OF, PARENT_OF,
                        exits),
                () -> new SubmachineSpec<String, DayOfWeek, Month, Lamp, Light, Flip>(DayOfWeek.MONDAY, null,
                        CHILD_OF, PARENT_OF, exits),
                () -> new SubmachineSpec<String, DayOfWeek, Month, Lamp, Light, Flip>(DayOfWeek.MONDAY, CHILD, null,
                        PARENT_OF, exits),
                () -> new SubmachineSpec<String, DayOfWeek, Month, Lamp, Light, Flip>(DayOfWeek.MONDAY, CHILD,
                        CHILD_OF, null, exits),
                () -> new SubmachineSpec<String, DayOfWeek, Month, Lamp, Light, Flip>(DayOfWeek.MONDAY, CHILD,
                        CHILD_OF, PARENT_OF, null));
        for (int i = 0; i < calls.size(); i++) {
            Runnable call = calls.get(i);
            NullPointerException error = assertThrows(NullPointerException.class, call::run);
            assertTrue(error.getMessage().startsWith(names.get(i)), error.getMessage());
        }
    }
}
