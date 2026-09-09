package io.github.graphmaquina.dsl;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import java.util.Collection;
import java.util.Optional;
import java.util.function.Function;

/** Stage that declares the cells of one row of the matrix, and starts or ends the row. */
public interface RowBuilder<E, S extends Enum<S>> {

    EdgeStart<E, S> to(S target);

    /** The (from, from) cell, with kind STAY. */
    StayEdge<E, S> stay();

    <P, Q extends Enum<Q>> WatchBuilder<E, S, P, Q> watches(MachineKey<P, Q> peer,
                                                            Function<? super E, ? extends Collection<? extends P>> peersOf,
                                                            Function<? super P, Optional<E>> ownerOf);

    /** FORBID for every cell of the row nobody declared. Closes the row. */
    GraphBuilder<E, S> forbidOthers(String reason);

    RowBuilder<E, S> from(S state);

    GraphDefinition<E, S> build();
}
