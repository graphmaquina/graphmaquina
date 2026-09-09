package io.github.graphmaquina.api;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import lombok.NonNull;

/** The watch of a state: which machine it looks at, how to reach the peers and back, and which rules advance it. */
public record WatchSpec<E, S extends Enum<S>, P, Q extends Enum<Q>>(
        @NonNull S state,
        @NonNull MachineKey<P, Q> peer,
        @NonNull Function<? super E, ? extends Collection<? extends P>> peersOf,
        @NonNull Function<? super P, Optional<E>> ownerOf,
        @NonNull List<WatchRule<E, S, P, Q>> rules) {

    public WatchSpec {
        rules = List.copyOf(rules);
        if (rules.isEmpty()) {
            throw new IllegalArgumentException("the watch of " + state + " has no rules");
        }
    }
}
