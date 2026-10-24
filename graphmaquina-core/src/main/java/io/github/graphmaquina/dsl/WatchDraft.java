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
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Draft of the watch declared on one state, accumulating its when(...).execute(...) rules. A condition is pending
 * until its execute(...) arrives; one that never gets it is reported, not dropped.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class WatchDraft<E, S extends Enum<S>, T extends Enum<T>, P, Q extends Enum<Q>, U extends Enum<U>>
        implements WatchBuilder<E, S, T, P, Q, U>, WatchRuleBuilder<E, S, T, P, Q, U> {

    private final DefaultGraphBuilder<E, S, T> graph;
    @Getter(AccessLevel.PACKAGE)
    private final S state;
    private final MachineKey<P, Q, U> peer;
    private final Function<? super E, ? extends Collection<? extends P>> peersOf;
    private final Function<? super P, Optional<E>> ownerOf;
    private final List<WatchRule<E, S, T, P, Q, U>> rules = new ArrayList<>();
    private Optional<NamedCondition<E, P, Q, U>> pending = Optional.empty();

    @Override
    public WatchRuleBuilder<E, S, T, P, Q, U> when(@NonNull NamedCondition<E, P, Q, U> condition) {
        requireUnbuilt();
        return pend(condition);
    }

    @Override
    public WatchRuleBuilder<E, S, T, P, Q, U> when(@NonNull String name,
                                                   @NonNull WatchCondition<E, P, Q, U> condition) {
        requireUnbuilt();
        if (name.isBlank()) {
            graph.problems().add("SG107", state.ordinal(), -1,
                    "a condition of the watch of " + state.name() + " has a blank name");
            return pend(new NamedCondition<>("(unnamed)", condition));
        }
        return pend(new NamedCondition<>(name, condition));
    }

    /** A condition still waiting for its execute(...) is reported as SG404 before the new one takes its place. */
    private WatchDraft<E, S, T, P, Q, U> pend(NamedCondition<E, P, Q, U> condition) {
        reportPending(graph.problems());
        pending = Optional.of(condition);
        return this;
    }

    @Override
    public WatchBuilder<E, S, T, P, Q, U> execute(@NonNull T transition) {
        requireUnbuilt();
        graph.requireTransition(transition);
        if (pending.isEmpty()) {
            graph.problems().add("SG404", state.ordinal(), transition.ordinal(),
                    "the watch of " + state.name() + " declares execute(" + transition.name() + ") without when(...)");
            return this;
        }
        rules.add(new WatchRule<>(pending.get(), transition));
        pending = Optional.empty();
        return this;
    }

    @Override
    public GraphBuilder<E, S, T> endWatch() {
        return graph;
    }

    /** Once the graph is built nothing reads this watch any more, so a late call would be lost. */
    private void requireUnbuilt() {
        graph.requireUnbuilt("the watch of " + state.name());
    }

    private void reportPending(Problems problems) {
        if (pending.isPresent()) {
            problems.add("SG404", state.ordinal(), -1, "the watch of " + state.name() + " declares when("
                    + pending.get().name() + ") without execute(...)");
        }
    }

    Optional<WatchSpec<E, S, T, P, Q, U>> toSpec(Problems problems) {
        reportPending(problems);
        if (rules.isEmpty()) {
            problems.add("SG404", state.ordinal(), -1,
                    "the watch of " + state.name() + " declares no when(...).execute(...) rule");
            return Optional.empty();
        }
        return Optional.of(new WatchSpec<>(state, peer, peersOf, ownerOf, rules));
    }
}
