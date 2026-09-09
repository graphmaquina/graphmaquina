package io.github.graphmaquina.codegen.model;

import java.util.List;
import java.util.Objects;

/** Everything one compilation unit's generator run resolved: its graphs and the symbols they need. */
public record GenerationModel(List<GraphDescriptor> graphs, List<SymbolDescriptor> symbols) {

    public GenerationModel {
        Objects.requireNonNull(graphs, "graphs");
        Objects.requireNonNull(symbols, "symbols");
        graphs = List.copyOf(graphs);
        symbols = List.copyOf(symbols);
    }
}
