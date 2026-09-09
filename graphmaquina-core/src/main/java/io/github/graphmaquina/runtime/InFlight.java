package io.github.graphmaquina.runtime;

/** An entity currently mid-step toward a target, used to detect cascade cycles. */
record InFlight(EntityRef ref, Enum<?> target) { }
