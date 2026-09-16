package io.github.graphmaquina.api;

import io.github.graphmaquina.graph.DirectedGraph;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** The only thing the runtime consults. The DSL implements it today; a processor generates it tomorrow. */
public interface GraphDefinition<E, S extends Enum<S>, T extends Enum<T>> {

    MachineKey<E, S, T> key();

    S initial();

    Set<S> terminals();

    /** All the states as nodes, all the declared edges as labeled edges, parallels included. */
    DirectedGraph<S, T> graph();

    /** Every declared edge, ordered by origin, then identifier, then destination. */
    List<TransitionSpec<E, S, T>> transitions();

    /** The edges leaving {@code state}, in the same order. */
    List<TransitionSpec<E, S, T>> outgoingFrom(S state);

    /** The single edge keyed by {@code (from, transition)}, if it was declared. Runs no effects. */
    Optional<TransitionSpec<E, S, T>> findTransition(S from, T transition);

    /** Never null: every state has a policy. */
    StatePolicy<S, T> policy(S state);

    Optional<WatchSpec<E, S, T, ?, ?, ?>> watch(S state);

    /** Some edge is asynchronous. */
    boolean hasAsync();
}
