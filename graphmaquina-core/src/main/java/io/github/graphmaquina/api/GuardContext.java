package io.github.graphmaquina.api;

import java.util.Objects;
import java.util.Optional;

/** A guard decides, it does not mutate: no cascade and no depth. */
public interface GuardContext<E, S extends Enum<S>> {

    E entity();

    S from();

    S to();

    <P, Q extends Enum<Q>> PeerView<P, Q> peer(MachineKey<P, Q> key);

    <P, Q extends Enum<Q>, M extends Machine<P, Q>> PeerView<P, Q> peer(Class<M> peer);

    /**
     * The value passed as this operation's second argument, if any; empty when called with one argument or an
     * explicit {@code null}. Scoped to this invocation only, never a value left over from an earlier call.
     */
    default Optional<?> input() {
        return Optional.empty();
    }

    /**
     * {@link #input()} cast to {@code type}: the same reference, without copying or converting it.
     *
     * @throws NullPointerException if {@code type} is null
     * @throws IllegalArgumentException if {@code type} is a primitive class token; use its wrapper class instead
     * @throws ClassCastException if an input is present but not assignable to {@code type}
     */
    default <I> Optional<I> input(Class<I> type) {
        Objects.requireNonNull(type, "type");
        if (type.isPrimitive()) {
            throw new IllegalArgumentException("input type must be a reference type; use its wrapper class");
        }
        return input().map(type::cast);
    }
}
