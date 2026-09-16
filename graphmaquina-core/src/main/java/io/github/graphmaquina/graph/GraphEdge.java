package io.github.graphmaquina.graph;

import lombok.NonNull;

/** Bare edge of a directed graph. The meaning lives in the machine's transitions, not here. */
public record GraphEdge<N>(@NonNull N from, @NonNull N to) {
}
