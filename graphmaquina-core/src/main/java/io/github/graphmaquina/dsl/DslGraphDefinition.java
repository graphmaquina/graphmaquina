package io.github.graphmaquina.dsl;

import io.github.graphmaquina.api.EdgeSpec;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.WatchSpec;
import io.github.graphmaquina.graph.Graph;
import io.github.graphmaquina.graph.GraphEdge;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.Getter;
import lombok.NonNull;

/** Immutable {@link GraphDefinition} assembled by the DSL from a complete, valid matrix of drafts. */
final class DslGraphDefinition<E, S extends Enum<S>> implements GraphDefinition<E, S> {

    @Getter
    private final MachineKey<E, S> key;
    private final S[] states;
    @Getter
    private final S initial;
    @Getter
    private final Set<S> terminals;
    private final List<EdgeSpec<E, S>> cells;
    @Getter
    private final Graph<S> graph;
    private final Map<S, WatchSpec<E, S, ?, ?>> watches;
    @Getter
    private final boolean hasAsync;
    private final String rendered;

    DslGraphDefinition(MachineKey<E, S> key, S[] states, S initial, Set<S> terminals,
                       Map<CellKey<S>, EdgeSpec<E, S>> specs, Map<S, WatchSpec<E, S, ?, ?>> watches) {
        this.key = key;
        this.states = states;
        this.initial = initial;
        EnumSet<S> terminalCopy = EnumSet.noneOf(key.states());
        terminalCopy.addAll(terminals);
        this.terminals = Collections.unmodifiableSet(terminalCopy);
        List<EdgeSpec<E, S>> ordered = new ArrayList<>();
        List<GraphEdge<S>> edges = new ArrayList<>();
        boolean async = false;
        for (S from : states) {
            for (S to : states) {
                EdgeSpec<E, S> spec = specs.get(new CellKey<>(from, to));
                ordered.add(spec);
                if (spec.moves()) {
                    edges.add(new GraphEdge<>(from, to));
                }
                if (spec.isAsync()) {
                    async = true;
                }
            }
        }
        this.cells = List.copyOf(ordered);
        this.graph = Graph.of(Arrays.asList(states), edges);
        this.watches = Collections.unmodifiableMap(new LinkedHashMap<>(watches));
        this.hasAsync = async;
        this.rendered = MatrixRenderer.render(states, this.cells);
    }

    @Override
    public EdgeSpec<E, S> edge(@NonNull S from, @NonNull S to) {
        return cells.get(from.ordinal() * states.length + to.ordinal());
    }

    @Override
    public Set<S> allowedFrom(@NonNull S state) {
        EnumSet<S> allowed = EnumSet.noneOf(key.states());
        for (S to : states) {
            if (edge(state, to).moves()) {
                allowed.add(to);
            }
        }
        return Collections.unmodifiableSet(allowed);
    }

    @Override
    public Optional<WatchSpec<E, S, ?, ?>> watch(@NonNull S state) {
        return Optional.ofNullable(watches.get(state));
    }

    @Override
    public String render() { return rendered; }

    @Override
    public String toString() { return rendered; }
}
