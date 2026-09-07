package io.stategraph.api;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** The watch of a state: which machine it looks at, how to reach the peers and back, and which rules advance it. */
public record WatchSpec<E, S extends Enum<S>, P, Q extends Enum<Q>>(
        S state,
        MachineKey<P, Q> peer,
        Function<? super E, ? extends Collection<? extends P>> peersOf,
        Function<? super P, Optional<E>> ownerOf,
        List<WatchRule<E, S, P, Q>> rules) {

    public WatchSpec {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(peer, "peer");
        Objects.requireNonNull(peersOf, "peersOf");
        Objects.requireNonNull(ownerOf, "ownerOf");
        Objects.requireNonNull(rules, "rules");
        rules = List.copyOf(rules);
        if (rules.isEmpty()) {
            throw new IllegalArgumentException("the watch of " + state + " has no rules");
        }
    }
}
