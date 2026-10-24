package io.github.graphmaquina.codegen.model;

import java.util.List;
import lombok.NonNull;

/**
 * Everything one generator run resolved for its compilation: the graph of every machine and the symbol of every
 * member its hosts export, whether or not a graph refers to it.
 */
public record GenerationModel(@NonNull List<GraphDescriptor> graphs, @NonNull List<SymbolDescriptor> symbols) {

    public GenerationModel {
        graphs = List.copyOf(graphs);
        symbols = List.copyOf(symbols);
    }
}
