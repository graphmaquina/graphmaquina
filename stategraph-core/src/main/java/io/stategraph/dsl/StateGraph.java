package io.stategraph.dsl;

import io.stategraph.api.MachineKey;
import lombok.NonNull;

/** Single entry point of the DSL. */
public final class StateGraph {

    private StateGraph() { }

    public static <E, S extends Enum<S>> GraphBuilder<E, S> define(@NonNull MachineKey<E, S> key) {
        return new DefaultGraphBuilder<>(key);
    }
}
