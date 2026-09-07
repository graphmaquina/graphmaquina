package io.stategraph.dsl;

import io.stategraph.api.GraphDefinition;
import io.stategraph.api.MachineKey;
import java.util.Collection;
import java.util.Optional;
import java.util.function.Function;
import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** Draft of one row of the matrix: forwards cell declarations to the owning builder. */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class RowDraft<E, S extends Enum<S>> implements RowBuilder<E, S> {

    private final DefaultGraphBuilder<E, S> graph;
    private final S from;

    @Override
    public EdgeStart<E, S> to(@NonNull S target) {
        return graph.declare(this, from, target);
    }

    @Override
    public StayEdge<E, S> stay() {
        return graph.declare(this, from, from).markStay();
    }

    @Override
    public <P, Q extends Enum<Q>> WatchBuilder<E, S, P, Q> watches(@NonNull MachineKey<P, Q> peer,
                                                                   @NonNull Function<? super E, ? extends Collection<? extends P>> peersOf,
                                                                   @NonNull Function<? super P, Optional<E>> ownerOf) {
        return graph.declareWatch(this, from, peer, peersOf, ownerOf);
    }

    @Override
    public GraphBuilder<E, S> forbidOthers(@NonNull String reason) {
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
