package io.github.graphmaquina.api;

import java.util.Collection;
import lombok.NonNull;

/** Predicate of a watch rule over the entity, its peers, and a consistent view of their states. */
@FunctionalInterface
public interface WatchCondition<E, P, Q extends Enum<Q>, U extends Enum<U>> {

    boolean test(E entity, Collection<? extends P> peers, PeerView<P, Q, U> view);

    /** Non-empty and all in {@code state}. */
    static <E, P, Q extends Enum<Q>, U extends Enum<U>> NamedCondition<E, P, Q, U> allIn(@NonNull Q state) {
        return new NamedCondition<>("allIn " + state.name(),
                (E entity, Collection<? extends P> peers, PeerView<P, Q, U> view) -> view.allIn(peers, state));
    }

    /** At least one in {@code state}. */
    static <E, P, Q extends Enum<Q>, U extends Enum<U>> NamedCondition<E, P, Q, U> anyIn(@NonNull Q state) {
        return new NamedCondition<>("anyIn " + state.name(),
                (E entity, Collection<? extends P> peers, PeerView<P, Q, U> view) -> view.anyIn(peers, state));
    }

    /** None in {@code state}; true when the collection is empty. */
    static <E, P, Q extends Enum<Q>, U extends Enum<U>> NamedCondition<E, P, Q, U> noneIn(@NonNull Q state) {
        return new NamedCondition<>("noneIn " + state.name(),
                (E entity, Collection<? extends P> peers, PeerView<P, Q, U> view) -> view.noneIn(peers, state));
    }
}
