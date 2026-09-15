package io.github.graphmaquina.api;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Mutable context available to a {@link TransitionAction} during a transition, including cascading to peers. */
public interface TransitionContext<E, S extends Enum<S>> {

    E entity();

    S from();

    S to();

    /** 0 at the root transition; n at the n-th level of cascade. */
    int depth();

    <P, Q extends Enum<Q>> PeerView<P, Q> peer(MachineKey<P, Q> key);

    <P, Q extends Enum<Q>> Outcome<P, Q> cascade(MachineKey<P, Q> key, P peer, Q target);

    <P, Q extends Enum<Q>> List<Outcome<P, Q>> cascadeAll(MachineKey<P, Q> key, Collection<? extends P> peers,
                                                          Q target, CascadePolicy policy);

    /** The peer machine identified by its facade type; the generics of {@code M} fix {@code P} and {@code Q}. */
    <P, Q extends Enum<Q>, M extends Machine<P, Q>> PeerView<P, Q> peer(Class<M> peer);

    <P, Q extends Enum<Q>, M extends Machine<P, Q>> Outcome<P, Q> cascade(Class<M> peer, P entity, Q target);

    <P, Q extends Enum<Q>, M extends Machine<P, Q>> List<Outcome<P, Q>> cascadeAll(Class<M> peer, Collection<? extends P> peers,
                                                                                  Q target, CascadePolicy policy);

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
