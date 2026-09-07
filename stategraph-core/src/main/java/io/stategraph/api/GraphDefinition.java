package io.stategraph.api;

import io.stategraph.graph.Graph;
import java.util.Optional;
import java.util.Set;

/** The only thing the runtime consults. The DSL implements it today; a processor will generate it tomorrow. */
public interface GraphDefinition<E, S extends Enum<S>> {

    MachineKey<E, S> key();

    S initial();

    Set<S> terminals();

    /** Never null: the matrix is complete by construction. */
    EdgeSpec<E, S> edge(S from, S to);

    /** Nodes = all states. Edges = only the cells that move. */
    Graph<S> graph();

    /** ACTION and MOVE destinations from the state. */
    Set<S> allowedFrom(S state);

    Optional<WatchSpec<E, S, ?, ?>> watch(S state);

    /** Some cell is asynchronous. */
    boolean hasAsync();

    /** The matrix as text. toString() delegates here. */
    String render();
}
