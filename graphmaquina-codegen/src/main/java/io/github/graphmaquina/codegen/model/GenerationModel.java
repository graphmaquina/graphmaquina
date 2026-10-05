package io.github.graphmaquina.codegen.model;

import java.util.List;
import java.util.Objects;

/**
 * Everything one generator run resolved for its compilation: the graph of every machine and the symbol of every
 * member its hosts export, whether or not a graph refers to it.
 */
public record GenerationModel(List<GraphDescriptor> graphs, List<SymbolDescriptor> symbols) {

    public GenerationModel {
        Objects.requireNonNull(graphs, "graphs");
        Objects.requireNonNull(symbols, "symbols");
        graphs = List.copyOf(graphs);
        symbols = List.copyOf(symbols);
    }
}
