package io.github.graphmaquina.api;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Context handed to a {@link TransitionAction} or an {@link AsyncTransitionAction}: the edge, the depth, peer views
 * and cascades. A cascade runs the peer's step in this transaction and returns Applied or Failed; once its arguments
 * are accepted, whatever else it ends in propagates and poisons the transaction, so the root cannot commit even if
 * the action catches it. Peer views and cascades work only on the thread that called {@code process()} and only
 * until the root transaction ends: elsewhere they throw {@code WrongThreadException}, afterwards
 * {@code StaleContextException}.
 */
public interface TransitionContext<E, S extends Enum<S>, T extends Enum<T>> {

    E entity();

    S from();

    /** The identifier that selected the edge being executed. */
    T transition();

    S to();

    /** 0 at the root transition; n at the n-th level of cascade. */
    int depth();

    <P, Q extends Enum<Q>, U extends Enum<U>> PeerView<P, Q, U> peer(MachineKey<P, Q, U> key);

    <P, Q extends Enum<Q>, U extends Enum<U>> Outcome<P, Q, U> cascade(MachineKey<P, Q, U> key, P peer, U transition);

    <P, Q extends Enum<Q>, U extends Enum<U>> List<Outcome<P, Q, U>> cascadeAll(MachineKey<P, Q, U> key,
                                                                                Collection<? extends P> peers,
                                                                                U transition, CascadePolicy policy);

    /** The peer machine identified by its facade type; the generics of {@code M} fix {@code P}, {@code Q} and {@code U}. */
    <P, Q extends Enum<Q>, U extends Enum<U>, M extends Machine<P, Q, U>> PeerView<P, Q, U> peer(Class<M> peer);

    <P, Q extends Enum<Q>, U extends Enum<U>, M extends Machine<P, Q, U>> Outcome<P, Q, U> cascade(Class<M> peer, P entity,
                                                                                                   U transition);

    <P, Q extends Enum<Q>, U extends Enum<U>, M extends Machine<P, Q, U>> List<Outcome<P, Q, U>> cascadeAll(
            Class<M> peer, Collection<? extends P> peers, U transition, CascadePolicy policy);

    /**
     * The value passed as this operation's second argument, if any; empty when called with one argument or an
     * explicit {@code null}, and always empty in a cascaded step or a reevaluation, which carry none. Scoped to this
     * invocation only, never a value left over from an earlier call.
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
        return InputSupport.cast(type, this::input);
    }
}
