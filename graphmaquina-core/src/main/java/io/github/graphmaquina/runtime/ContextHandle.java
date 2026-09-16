package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.MachineKey;

/** What the transaction needs to know about a live context, without its generics. */
interface ContextHandle {

    MachineKey<?, ?, ?> machineKey();

    Object entityInstance();

    Enum<?> fromState();

    Enum<?> requestedTransition();

    Enum<?> toState();

    int contextDepth();

    void invalidate();
}
