package io.stategraph.graph;

import lombok.NonNull;

/** Bare edge of a directed graph. The meaning lives in EdgeSpec, not here. */
public record GraphEdge<N>(@NonNull N from, @NonNull N to) {
}
