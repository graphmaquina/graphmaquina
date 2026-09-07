package io.stategraph.graph;

import java.util.Objects;

/** Bare edge of a directed graph. The meaning lives in EdgeSpec, not here. */
public record GraphEdge<N>(N from, N to) {

    public GraphEdge {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
    }
}
