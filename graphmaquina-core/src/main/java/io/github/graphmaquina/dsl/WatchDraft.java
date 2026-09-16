package io.github.graphmaquina.dsl;

import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.NamedCondition;
import io.github.graphmaquina.api.WatchCondition;
import io.github.graphmaquina.api.WatchRule;
import io.github.graphmaquina.api.WatchSpec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** Draft of the watch declared on one state, accumulating its when(...).execute(...) rules. */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class WatchDraft<E, S extends Enum<S>, T extends Enum<T>, P, Q extends Enum<Q>, U extends Enum<U>>
        implements WatchBuilder<E, S, T, P, Q, U>, WatchRuleBuilder<E, S, T, P, Q, U> {

    private final DefaultGraphBuilder<E, S, T> graph;
    private final S state;
    private final MachineKey<P, Q, U> peer;
    private final Function<? super E, ? extends Collection<? extends P>> peersOf;
    private final Function<? super P, Optional<E>> ownerOf;
    private final List<WatchRule<E, S, T, P, Q, U>> rules = new ArrayList<>();
    private Optional<NamedCondition<E, P, Q, U>> pending = Optional.empty();

    S state() { return state; }

    @Override
    public WatchRuleBuilder<E, S, T, P, Q, U> when(@NonNull NamedCondition<E, P, Q, U> condition) {
        pending = Optional.of(condition);
        return this;
    }

    @Override
    public WatchRuleBuilder<E, S, T, P, Q, U> when(@NonNull String name, @NonNull WatchCondition<E, P, Q, U> condition) {
        if (name.isBlank()) {
            graph.problems().add("SG107", state.ordinal(), -1,
                    "a condition of the watch of " + state + " has a blank name");
            pending = Optional.of(new NamedCondition<>("(unnamed)", condition));
        } else {
            pending = Optional.of(new NamedCondition<>(name, condition));
        }
        return this;
    }

    @Override
    public WatchBuilder<E, S, T, P, Q, U> execute(@NonNull T transition) {
        rules.add(new WatchRule<>(pending.orElseThrow(), transition));
        pending = Optional.empty();
        return this;
    }

    @Override
    public GraphBuilder<E, S, T> endWatch() {
        return graph;
    }

    Optional<WatchSpec<E, S, T, P, Q, U>> toSpec(Problems problems) {
        if (rules.isEmpty()) {
            problems.add("SG404", state.ordinal(), -1,
                    "the watch of " + state + " declares no when(...).execute(...) rule");
            return Optional.empty();
        }
        return Optional.of(new WatchSpec<>(state, peer, peersOf, ownerOf, rules));
    }
}
