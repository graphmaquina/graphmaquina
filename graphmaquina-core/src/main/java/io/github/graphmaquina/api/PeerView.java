package io.github.graphmaquina.api;

import java.util.Collection;
import lombok.NonNull;

/** Read-only view over another machine. Reads the UnitOfWork first, then the entity. */
public interface PeerView<P, Q extends Enum<Q>, U extends Enum<U>> {

    MachineKey<P, Q, U> key();

    Q stateOf(P peer);

    /** Non-empty and all in {@code state}. */
    default boolean allIn(@NonNull Collection<? extends P> peers, @NonNull Q state) {
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

    default boolean anyIn(@NonNull Collection<? extends P> peers, @NonNull Q state) {
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
