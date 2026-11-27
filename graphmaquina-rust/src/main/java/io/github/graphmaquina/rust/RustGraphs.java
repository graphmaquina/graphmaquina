package io.github.graphmaquina.rust;

import io.github.graphmaquina.graph.Graph;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.NonNull;

/**
 * Two graph algorithms of the library answered by the Rust kernels: {@code StrongComponents.find} and
 * {@code Graph.cycles()}. The graph is numbered once in its declared node order and handed over as compressed sparse
 * rows, successors in the order the graph declares them; the answer comes back as node numbers and is turned into
 * the same lists of the graph's own node instances, unmodifiable as the library's are.
 */
public final class RustGraphs {

    private RustGraphs() { }

    /** What {@code StrongComponents.find(graph)} returns. */
    public static <N> List<Set<N>> components(@NonNull Graph<N> graph) {
        Rows<N> rows = Rows.of(graph);
        int[] component = new int[rows.order().size()];
        int count = RustKernels.strongComponents(rows.offsets(), rows.targets(), component);
        List<List<N>> members = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            members.add(new ArrayList<>());
        }
        for (int node = 0; node < component.length; node++) {
            members.get(component[node]).add(rows.order().get(node));
        }
        List<Set<N>> frozen = new ArrayList<>(count);
        for (List<N> list : members) {
            frozen.add(Collections.unmodifiableSet(new LinkedHashSet<>(list)));
        }
        return List.copyOf(frozen);
    }

    /** What {@code graph.cycles()} returns. */
    public static <N> List<List<N>> cycles(@NonNull Graph<N> graph) {
        Rows<N> rows = Rows.of(graph);
        int[] listing = RustKernels.cycles(rows.offsets(), rows.targets());
        List<List<N>> cycles = new ArrayList<>();
        int at = 0;
        while (at < listing.length) {
            int length = listing[at++];
            @SuppressWarnings("unchecked")
            N[] cycle = (N[]) new Object[length];
            for (int i = 0; i < length; i++) {
                cycle[i] = rows.order().get(listing[at++]);
            }
            cycles.add(List.of(cycle));
        }
        return List.copyOf(cycles);
    }

    /** A graph as compressed sparse rows over the positions of its nodes in declared order. */
    private record Rows<N>(List<N> order, int[] offsets, int[] targets) {

        static <N> Rows<N> of(Graph<N> graph) {
            List<N> order = new ArrayList<>(graph.nodes());
            Map<N, Integer> position = HashMap.newHashMap(order.size());
            for (int i = 0; i < order.size(); i++) {
                position.put(order.get(i), i);
            }
            int[] offsets = new int[order.size() + 1];
            // The edge set and the successor sets collapse the same repeats, so their sizes agree.
            int[] targets = new int[graph.edges().size()];
            int at = 0;
            for (int node = 0; node < order.size(); node++) {
                offsets[node] = at;
                for (N successor : graph.successors(order.get(node))) {
                    targets[at++] = position.get(successor);
                }
            }
            offsets[order.size()] = at;
            return new Rows<>(order, offsets, targets);
        }
    }
}
