package io.github.graphmaquina.dsl;

import io.github.graphmaquina.graph.Graph;
import io.github.graphmaquina.graph.GraphEdge;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What one validation needs to read again and again about a {@link MachineOutline}: the declaration index of
 * every state and of every transition identifier, the edges that leave each state, and the node topology of
 * the declared edges. It is built once per validation from a single pass over each list and a single pass over
 * the edges, and then never changes, so no diagnostic has to scan a list to position itself.
 *
 * <p>It indexes what the outline holds, valid or not: an outline may declare two edges under the same key and
 * both stay, in declaration order, because reporting that duplicate is the validator's job. Answers match
 * {@link MachineOutline} exactly — an unknown name has index -1 and leaves through nothing.
 *
 * <p>It is only ever built from a constructed {@link MachineOutline}, and that constructor has already rejected
 * duplicate states, duplicate identifiers and any edge endpoint that is not a declared state. That is the
 * precondition the single pass over the edges relies on: it adds no check of its own, because invalid data must
 * stay representable for the validator to report, and an outline breaking the precondition would fail while this
 * index is built rather than be diagnosed.
 */
final class OutlineIndex {

    private final Map<String, Integer> states;
    private final Map<String, Integer> transitions;
    private final Map<String, List<TransitionOutline>> leaving;
    private final Graph<String> topology;

    OutlineIndex(MachineOutline outline) {
        List<String> declaredStates = outline.states();
        Map<String, Integer> stateIndexes = new HashMap<>();
        Map<String, List<TransitionOutline>> outgoing = new LinkedHashMap<>();
        for (int i = 0; i < declaredStates.size(); i++) {
            stateIndexes.putIfAbsent(declaredStates.get(i), i);
            outgoing.put(declaredStates.get(i), new ArrayList<>());
        }
        List<String> declaredCatalog = outline.catalog();
        Map<String, Integer> transitionIndexes = new HashMap<>();
        for (int i = 0; i < declaredCatalog.size(); i++) {
            transitionIndexes.putIfAbsent(declaredCatalog.get(i), i);
        }
        List<GraphEdge<String>> edges = new ArrayList<>();
        for (TransitionOutline edge : outline.transitions()) {
            outgoing.get(edge.from()).add(edge);
            edges.add(new GraphEdge<>(edge.from(), edge.to()));
        }
        Map<String, List<TransitionOutline>> frozen = new LinkedHashMap<>();
        for (Map.Entry<String, List<TransitionOutline>> entry : outgoing.entrySet()) {
            frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        this.states = Collections.unmodifiableMap(stateIndexes);
        this.transitions = Collections.unmodifiableMap(transitionIndexes);
        this.leaving = Collections.unmodifiableMap(frozen);
        this.topology = Graph.of(declaredStates, edges);
    }

    int stateIndex(String state) {
        Integer index = states.get(state);
        return index == null ? -1 : index.intValue();
    }

    int transitionIndex(String transition) {
        Integer index = transitions.get(transition);
        return index == null ? -1 : index.intValue();
    }

    /** The edges leaving {@code state}, in declaration order; empty for a state that declares none. */
    List<TransitionOutline> outgoing(String state) {
        return leaving.getOrDefault(state, List.of());
    }

    /** Node topology of the outline: parallel edges collapse, which is exactly what reachability needs. */
    Graph<String> topology() {
        return topology;
    }
}
