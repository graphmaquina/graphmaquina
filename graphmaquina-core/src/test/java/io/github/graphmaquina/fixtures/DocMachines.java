package io.github.graphmaquina.fixtures;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.runtime.MachineRegistry;
import io.github.graphmaquina.runtime.StateMachines;
import java.time.Duration;
import java.util.function.BiConsumer;

/**
 * Registries that hold the doc fixture machine over a graph that the test shapes, for example with {@link DocGraphs}.
 */
public final class DocMachines {

    private DocMachines() { }

    /** A registry with the doc machine over {@code graph}. */
    public static MachineRegistry registry(GraphDefinition<Doc, DocStatus, DocTransition> graph) {
        return registry(graph, Doc::setStatus);
    }

    /** {@link #registry(GraphDefinition)} whose state setter is {@code setter}. */
    public static MachineRegistry registry(GraphDefinition<Doc, DocStatus, DocTransition> graph,
                                           BiConsumer<Doc, DocStatus> setter) {
        return MachineRegistry.builder()
                .add(StateMachines.from(graph).state(Doc::status, setter).identity(Doc::id))
                .build();
    }

    /** {@link #registry(GraphDefinition)} for a graph with an async action, which needs an asyncTimeout. */
    public static MachineRegistry registry(GraphDefinition<Doc, DocStatus, DocTransition> graph,
                                           Duration asyncTimeout) {
        return MachineRegistry.builder()
                .add(StateMachines.from(graph).state(Doc::status, Doc::setStatus).identity(Doc::id)
                        .asyncTimeout(asyncTimeout))
                .build();
    }
}
