package io.stategraph.graph;

import java.util.Objects;

/** Arista desnuda de un grafo dirigido. El significado vive en EdgeSpec, no aquí. */
public record GraphEdge<N>(N from, N to) {

    public GraphEdge {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
    }
}
