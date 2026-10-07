package io.github.graphmaquina.api;

import java.util.Collection;
import lombok.NonNull;

/**
 * Read-only view over a machine's states. The view a context hands out reads the transaction's pending state first,
 * then the entity; the view a watch condition receives reads the entity only. An exception the entity's identity
 * function or state getter throws, or a null state, reaches the caller of {@code stateOf} as it is.
 */
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
