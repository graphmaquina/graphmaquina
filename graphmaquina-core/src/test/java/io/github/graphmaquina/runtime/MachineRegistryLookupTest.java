package io.github.graphmaquina.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.error.UnknownPeerMachineException;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A registry finds a machine by the value of its key, whatever its size: a key equal to a registered one but another
 * instance finds the very machine the registered instance does, through {@code of} and through the engine's own
 * lookup, and a key of no machine is refused with the keys registered. Up to {@link MachineRegistry}'s scan limit of
 * 8 machines the lookup compares key references first, so the sizes cover one machine, the limit and one more.
 */
class MachineRegistryLookupTest {

    /** States of every machine here. */
    enum Phase { OPEN, CLOSED }

    /** Identifiers of every machine here. */
    enum Move { CLOSE }

    /** The entity of each machine, in the order the registries add them; one more than the scan limit. */
    private static final List<Class<?>> ENTITIES = List.of(String.class, Integer.class, Long.class, Short.class,
            Byte.class, Double.class, Float.class, Character.class, Boolean.class);

    /** OPEN -CLOSE-> CLOSED over {@code entity}, whose state is always OPEN. */
    private static <E> StateMachines.Builder<E, Phase, Move> machineOver(Class<E> entity) {
        GraphDefinition<E, Phase, Move> graph = StateGraph.define(MachineKey.of(entity, Phase.class, Move.class))
                .initial(Phase.OPEN).terminal(Phase.CLOSED)
                .statePolicy(Phase.OPEN, "an open entity is closed")
                .transition(Move.CLOSE, Phase.OPEN, Phase.CLOSED).endTransition()
                .build();
        return StateMachines.from(graph)
                .state((E instance) -> Phase.OPEN, (E instance, Phase state) -> { })
                .identity((E instance) -> instance);
    }

    /** A registry of the first {@code size} machines. */
    private static MachineRegistry registryOf(int size) {
        MachineRegistry.Builder builder = MachineRegistry.builder();
        for (Class<?> entity : ENTITIES.subList(0, size)) {
            builder.add(machineOver(entity));
        }
        return builder.build();
    }

    /** A new key with the classes of {@code key}: equal to it, and another instance. */
    private static <E, S extends Enum<S>, T extends Enum<T>> MachineKey<E, S, T> copyOf(MachineKey<E, S, T> key) {
        return MachineKey.of(key.entity(), key.states(), key.transitions());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 8, 9})
    void aKeyEqualToARegisteredOneButAnotherInstanceFindsTheSameMachine(int size) {
        try (MachineRegistry registry = registryOf(size)) {
            assertEquals(size, registry.keys().size());
            for (MachineKey<?, ?, ?> registered : registry.keys()) {
                MachineKey<?, ?, ?> equal = copyOf(registered);
                assertNotSame(registered, equal);
                assertEquals(registered, equal);
                DefaultStateMachine<?, ?, ?> machine = registry.anyMachineFor(registered);
                assertSame(registered, machine.key());
                assertSame(machine, registry.anyMachineFor(equal));
                assertSame(machine, registry.of(equal));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 8, 9})
    void aKeyOfNoMachineIsRefusedWithTheKeysRegistered(int size) {
        MachineKey<StringBuilder, Phase, Move> unknown = MachineKey.of(StringBuilder.class, Phase.class, Move.class);
        try (MachineRegistry registry = registryOf(size)) {
            UnknownPeerMachineException refused =
                    assertThrows(UnknownPeerMachineException.class, () -> registry.anyMachineFor(unknown));
            assertSame(unknown, refused.requested());
            assertEquals(registry.keys(), refused.registered());
            assertThrows(UnknownPeerMachineException.class, () -> registry.of(unknown));
        }
    }
}
