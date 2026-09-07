package io.stategraph.dsl;

import io.stategraph.api.GraphDefinition;
import io.stategraph.api.MachineKey;
import io.stategraph.api.NamedCondition;
import io.stategraph.api.WatchCondition;
import io.stategraph.api.WatchRule;
import io.stategraph.api.WatchSpec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** Draft of the watch declared on one state, accumulating its when(...).goTo(...) rules. */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class WatchDraft<E, S extends Enum<S>, P, Q extends Enum<Q>>
        implements WatchBuilder<E, S, P, Q>, WatchRuleBuilder<E, S, P, Q> {

    private final DefaultGraphBuilder<E, S> graph;
    private final RowDraft<E, S> row;
    private final S state;
    private final MachineKey<P, Q> peer;
    private final Function<? super E, ? extends Collection<? extends P>> peersOf;
    private final Function<? super P, Optional<E>> ownerOf;
    private final List<WatchRule<E, S, P, Q>> rules = new ArrayList<>();
    private Optional<NamedCondition<E, P, Q>> pending = Optional.empty();

    S state() { return state; }

    @Override
    public WatchRuleBuilder<E, S, P, Q> when(@NonNull NamedCondition<E, P, Q> condition) {
        pending = Optional.of(condition);
        return this;
    }

    @Override
    public WatchRuleBuilder<E, S, P, Q> when(@NonNull String name, @NonNull WatchCondition<E, P, Q> condition) {
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
    public WatchBuilder<E, S, P, Q> goTo(@NonNull S target) {
        rules.add(new WatchRule<>(pending.orElseThrow(), target));
        pending = Optional.empty();
        return this;
    }

    Optional<WatchSpec<E, S, P, Q>> toSpec(Problems problems) {
        if (rules.isEmpty()) {
            problems.add("SG404", state.ordinal(), -1,
                    "the watch of " + state + " declares no when(...).goTo(...) rule");
            return Optional.empty();
        }
        return Optional.of(new WatchSpec<>(state, peer, peersOf, ownerOf, rules));
    }

    // RowBuilder: delegates to the row

    @Override
    public EdgeStart<E, S> to(S target) { return row.to(target); }

    @Override
    public StayEdge<E, S> stay() { return row.stay(); }

    @Override
    public <P2, Q2 extends Enum<Q2>> WatchBuilder<E, S, P2, Q2> watches(MachineKey<P2, Q2> otherPeer,
                                                                        Function<? super E, ? extends Collection<? extends P2>> otherPeersOf,
                                                                        Function<? super P2, Optional<E>> otherOwnerOf) {
        return row.watches(otherPeer, otherPeersOf, otherOwnerOf);
    }

    @Override
    public GraphBuilder<E, S> forbidOthers(String reason) { return row.forbidOthers(reason); }

    @Override
    public RowBuilder<E, S> from(S other) { return row.from(other); }

    @Override
    public GraphDefinition<E, S> build() { return row.build(); }
}
