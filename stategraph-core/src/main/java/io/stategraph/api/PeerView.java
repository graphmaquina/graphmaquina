package io.stategraph.api;

import java.util.Collection;
import java.util.Objects;

/** Read-only view over another machine. Reads the UnitOfWork first, then the entity. */
public interface PeerView<P, Q extends Enum<Q>> {

    MachineKey<P, Q> key();

    Q stateOf(P peer);

    /** Non-empty and all in {@code state}. */
    default boolean allIn(Collection<? extends P> peers, Q state) {
        Objects.requireNonNull(peers, "peers");
        Objects.requireNonNull(state, "state");
        if (peers.isEmpty()) {
            return false;
        }
        for (P peer : peers) {
            if (stateOf(peer) != state) {
                return false;
            }
        }
        return true;
    }

    default boolean anyIn(Collection<? extends P> peers, Q state) {
        Objects.requireNonNull(peers, "peers");
        Objects.requireNonNull(state, "state");
        for (P peer : peers) {
            if (stateOf(peer) == state) {
                return true;
            }
        }
        return false;
    }

    /** None in {@code state}; true when the collection is empty. */
    default boolean noneIn(Collection<? extends P> peers, Q state) {
        return !anyIn(peers, state);
    }
}
