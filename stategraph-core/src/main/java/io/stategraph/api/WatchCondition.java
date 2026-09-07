package io.stategraph.api;

import java.util.Collection;
import java.util.Objects;

/** Predicate of a watch rule over the entity, its peers, and a consistent view of their states. */
@FunctionalInterface
public interface WatchCondition<E, P, Q extends Enum<Q>> {

    boolean test(E entity, Collection<? extends P> peers, PeerView<P, Q> view);

    /** Non-empty and all in {@code state}. */
    static <E, P, Q extends Enum<Q>> NamedCondition<E, P, Q> allIn(Q state) {
        Objects.requireNonNull(state, "state");
        return new NamedCondition<>("allIn " + state.name(),
                (E entity, Collection<? extends P> peers, PeerView<P, Q> view) -> view.allIn(peers, state));
    }

    /** At least one in {@code state}. */
    static <E, P, Q extends Enum<Q>> NamedCondition<E, P, Q> anyIn(Q state) {
        Objects.requireNonNull(state, "state");
        return new NamedCondition<>("anyIn " + state.name(),
                (E entity, Collection<? extends P> peers, PeerView<P, Q> view) -> view.anyIn(peers, state));
    }

    /** None in {@code state}; true when the collection is empty. */
    static <E, P, Q extends Enum<Q>> NamedCondition<E, P, Q> noneIn(Q state) {
        Objects.requireNonNull(state, "state");
        return new NamedCondition<>("noneIn " + state.name(),
                (E entity, Collection<? extends P> peers, PeerView<P, Q> view) -> view.noneIn(peers, state));
    }
}
