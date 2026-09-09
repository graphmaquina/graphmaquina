package io.github.graphmaquina.api;

/** A guard decides, it does not mutate: no cascade and no depth. */
public interface GuardContext<E, S extends Enum<S>> {

    E entity();

    S from();

    S to();

    <P, Q extends Enum<Q>> PeerView<P, Q> peer(MachineKey<P, Q> key);

    <P, Q extends Enum<Q>, M extends Machine<P, Q>> PeerView<P, Q> peer(Class<M> peer);
}
