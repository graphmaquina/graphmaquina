package io.github.graphmaquina.dsl;

import io.github.graphmaquina.api.MachineKey;
import lombok.NonNull;

/** Single entry point of the DSL. */
public final class StateGraph {

    private StateGraph() { }

    public static <E, S extends Enum<S>, T extends Enum<T>> GraphBuilder<E, S, T> define(@NonNull MachineKey<E, S, T> key) {
        return new DefaultGraphBuilder<>(key);
    }
}
