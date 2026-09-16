package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.TransitionSpec;

/** An entity currently mid-step along an edge, used to detect cascade cycles. */
record InFlight(EntityRef ref, TransitionSpec<?, ?, ?> spec) {

    Enum<?> transition() { return spec.transition(); }
}
