package io.github.graphmaquina.graph;

import lombok.NonNull;

/**
 * A labeled directed edge between two nodes, used by {@link DirectedGraph}. Two edges are equal exactly
 * when their origin, label and destination all match; edges differing in any one of those three
 * components are distinct, which is what lets {@link DirectedGraph} keep parallel edges and self-loops.
 * All three components are required; none may be {@code null}.
 *
 * @param <N> node type
 * @param <L> edge label type
 * @param from origin node
 * @param transition edge label
 * @param to destination node
 */
public record DirectedEdge<N, L>(@NonNull N from, @NonNull L transition, @NonNull N to) {

    public DirectedEdge {
    }
}
