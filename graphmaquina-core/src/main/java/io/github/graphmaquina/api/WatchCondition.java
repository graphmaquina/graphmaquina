package io.github.graphmaquina.api;

import java.util.Collection;
import lombok.NonNull;

/** Predicate of a watch rule over the entity, its peers, and a consistent view of their states. */
@FunctionalInterface
public interface WatchCondition<E, P, Q extends Enum<Q>> {

    boolean test(E entity, Collection<? extends P> peers, PeerView<P, Q> view);

    /** Non-empty and all in {@code state}. */
    static <E, P, Q extends Enum<Q>> NamedCondition<E, P, Q> allIn(@NonNull Q state) {
        return new NamedCondition<>("allIn " + state.name(),
                (E entity, Collection<? extends P> peers, PeerView<P, Q> view) -> view.allIn(peers, state));
    }

    /** At least one in {@code state}. */
    static <E, P, Q extends Enum<Q>> NamedCondition<E, P, Q> anyIn(@NonNull Q state) {
        return new NamedCondition<>("anyIn " + state.name(),
                (E entity, Collection<? extends P> peers, PeerView<P, Q> view) -> view.anyIn(peers, state));
    }

    /** None in {@code state}; true when the collection is empty. */
    static <E, P, Q extends Enum<Q>> NamedCondition<E, P, Q> noneIn(@NonNull Q state) {
        return new NamedCondition<>("noneIn " + state.name(),
                (E entity, Collection<? extends P> peers, PeerView<P, Q> view) -> view.noneIn(peers, state));
    }
}
