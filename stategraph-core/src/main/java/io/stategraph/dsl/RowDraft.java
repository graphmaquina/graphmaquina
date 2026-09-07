package io.stategraph.dsl;

import io.stategraph.api.GraphDefinition;
import io.stategraph.api.MachineKey;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Draft of one row of the matrix: forwards cell declarations to the owning builder. */
final class RowDraft<E, S extends Enum<S>> implements RowBuilder<E, S> {

    private final DefaultGraphBuilder<E, S> graph;
    private final S from;

    RowDraft(DefaultGraphBuilder<E, S> graph, S from) {
        this.graph = graph;
        this.from = from;
    }

    @Override
    public EdgeStart<E, S> to(S target) {
        Objects.requireNonNull(target, "target");
        return graph.declare(this, from, target);
    }

    @Override
    public StayEdge<E, S> stay() {
        return graph.declare(this, from, from).markStay();
    }

    @Override
    public <P, Q extends Enum<Q>> WatchBuilder<E, S, P, Q> watches(MachineKey<P, Q> peer,
                                                                   Function<? super E, ? extends Collection<? extends P>> peersOf,
                                                                   Function<? super P, Optional<E>> ownerOf) {
        Objects.requireNonNull(peer, "peer");
        Objects.requireNonNull(peersOf, "peersOf");
        Objects.requireNonNull(ownerOf, "ownerOf");
        return graph.declareWatch(this, from, peer, peersOf, ownerOf);
    }

    @Override
    public GraphBuilder<E, S> forbidOthers(String reason) {
        Objects.requireNonNull(reason, "reason");
        graph.forbidRow(from, reason);
        return graph;
    }

    @Override
    public RowBuilder<E, S> from(S state) {
        return graph.from(state);
    }

    @Override
    public GraphDefinition<E, S> build() {
        return graph.build();
    }
}
