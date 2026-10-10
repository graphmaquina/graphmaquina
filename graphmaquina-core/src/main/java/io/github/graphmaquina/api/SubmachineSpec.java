package io.github.graphmaquina.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import lombok.NonNull;

/**
 * A sub-machine bound to one state of a parent graph: the child's definition, how to reach the child from the parent
 * and back, and the parent identifier that each terminal state of the child selects, its exit. When a step of the child
 * that began with the parent holding it ends in that terminal state, the parent is not in flight, and the call neither
 * moved it out of the bound state nor entered it again, the engine runs the exit on the parent in the same unit of
 * work. Validates nothing about the graphs; the
 * DSL does, when it builds the parent graph, and so does the annotation processor, when it reads a binding declared
 * with annotations.
 *
 * @param <E> entity of the parent machine
 * @param <S> state enum of the parent machine
 * @param <T> transition identifier enum of the parent machine
 * @param <P> entity of the child machine
 * @param <Q> state enum of the child machine
 * @param <U> transition identifier enum of the child machine
 * @param state the parent state the child is bound to
 * @param child the child machine's own definition
 * @param childOf the child entity of a parent entity, if it has one
 * @param parentOf the parent entity of a child entity, if it has one
 * @param exits the parent identifier of each terminal state of the child, copied in the given order into a map that
 *     cannot be modified; a null terminal or identifier is refused with a {@link NullPointerException} whose message
 *     is {@code exit terminal} or {@code exit transition}
 */
public record SubmachineSpec<E, S extends Enum<S>, T extends Enum<T>, P, Q extends Enum<Q>, U extends Enum<U>>(
        @NonNull S state,
        @NonNull GraphDefinition<P, Q, U> child,
        @NonNull Function<? super E, Optional<P>> childOf,
        @NonNull Function<? super P, Optional<E>> parentOf,
        @NonNull Map<Q, T> exits) {

    public SubmachineSpec {
        Map<Q, T> ordered = new LinkedHashMap<>();
        exits.forEach((Q terminal, T transition) -> ordered.put(
                Objects.requireNonNull(terminal, "exit terminal"),
                Objects.requireNonNull(transition, "exit transition")));
        exits = Collections.unmodifiableMap(ordered);
    }

    /** The key of the child machine, {@code child().key()}. */
    public MachineKey<P, Q, U> childKey() {
        return child.key();
    }
}
