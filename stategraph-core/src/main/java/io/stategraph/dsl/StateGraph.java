package io.stategraph.dsl;

import io.stategraph.api.MachineKey;
import java.util.Objects;

/** Single entry point of the DSL. */
public final class StateGraph {

    private StateGraph() { }

    public static <E, S extends Enum<S>> GraphBuilder<E, S> define(MachineKey<E, S> key) {
        Objects.requireNonNull(key, "key");
        return new DefaultGraphBuilder<>(key);
    }
}
