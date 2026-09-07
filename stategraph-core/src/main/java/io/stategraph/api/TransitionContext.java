package io.stategraph.api;

import java.util.Collection;
import java.util.List;

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
}
