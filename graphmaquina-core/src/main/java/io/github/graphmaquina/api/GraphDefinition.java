package io.github.graphmaquina.api;

import io.github.graphmaquina.graph.DirectedGraph;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The graph as the runtime reads it: the runtime reaches edges, policies, watches, sub-machines and terminal states
 * only through this interface; {@link #submachine(Enum)} returns the sub-machine bound to each state. The library's
 * only implementation is the one the DSL builds, and the code the annotation processor generates builds its graph
 * through the DSL. The runtime does not validate an implementation built otherwise. When a failure selects a recovery
 * identifier, through a recovery rule or onFailure, that names no edge leaving the failing edge's origin, the call
 * aborts in ACTION with a {@code TransitionAbortedException}. So does a call whose child reaches a terminal state that
 * its binding maps to no exit, or to an identifier with no edge leaving the bound state. An {@link #automaticFrom}
 * that throws aborts an advance, or an automatic attempt, in RESOLVE, and ends an automatic chain that reads it before
 * an attempt, or after one, as an {@link AutomaticStep.Aborted}.
 */
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

    /**
     * The immutable list of the automatic edges leaving {@code state}, in the order the graph declares them; empty when
     * the state has none. It holds exactly the edges leaving {@code state} whose {@link TransitionSpec#automatic()} is
     * true.
     */
    List<TransitionSpec<E, S, T>> automaticFrom(S state);

    /** The single edge keyed by {@code (from, transition)}, if it was declared. Runs no effects. */
    Optional<TransitionSpec<E, S, T>> findTransition(S from, T transition);

    /** Never null: every state has a policy. */
    StatePolicy<S, T> policy(S state);

    Optional<WatchSpec<E, S, T, ?, ?, ?>> watch(S state);

    /** The sub-machine bound to {@code state}, if any. Runs no lookup. */
    Optional<SubmachineSpec<E, S, T, ?, ?, ?>> submachine(S state);

    /** Some edge is asynchronous. */
    boolean hasAsync();
}
