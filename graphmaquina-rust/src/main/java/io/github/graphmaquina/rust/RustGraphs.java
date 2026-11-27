package io.github.graphmaquina.rust;

import io.github.graphmaquina.graph.Graph;
import io.github.graphmaquina.graph.GraphEdge;
import io.github.graphmaquina.graph.StrongComponents;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.NonNull;

/**
 * Two graph algorithms of the library answered by the Rust kernels: {@code StrongComponents.find} and
 * {@code Graph.cycles()}. The graph is numbered once in its declared node order and handed over as compressed sparse
 * rows, successors in the order the graph declares them; the answer comes back as node numbers and is turned into
 * the same lists, unmodifiable as the library's are.
 *
 * <p>Node numbers carry no identity, and the library's answers hold whichever instance its search met, which for an
 * edge that names an equal but distinct object is that object, not the declared one. So the kernel answers only a
 * graph where that cannot matter: every node is a String, a boxed primitive or an enum constant (final JDK types
 * whose {@code equals} and {@code hashCode} keep their contract and run no user code), and every edge names the
 * declared instances. Any other graph is answered by the library itself, so the result is always the library's,
 * instance for instance.
 */
public final class RustGraphs {

    private RustGraphs() { }

    /** What {@code StrongComponents.find(graph)} returns. */
    public static <N> List<Set<N>> components(@NonNull Graph<N> graph) {
        Optional<Rows<N>> numbered = Rows.of(graph);
        if (numbered.isEmpty()) {
            return StrongComponents.find(graph);
        }
        Rows<N> rows = numbered.get();
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
        Optional<Rows<N>> numbered = Rows.of(graph);
        if (numbered.isEmpty()) {
            return graph.cycles();
        }
        Rows<N> rows = numbered.get();
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

    /** Whether the kernel answers {@code graph}, rather than the library. */
    public static <N> boolean usesKernel(@NonNull Graph<N> graph) {
        return Rows.of(graph).isPresent();
    }

    /** A final JDK type whose {@code equals} and {@code hashCode} keep their contract and run no user code. */
    private static boolean lawful(Object node) {
        return node instanceof String || node instanceof Enum<?> || node instanceof Integer || node instanceof Long
                || node instanceof Short || node instanceof Byte || node instanceof Character
                || node instanceof Boolean;
    }

    /** A graph as compressed sparse rows over the positions of its nodes in declared order. */
    private record Rows<N>(List<N> order, int[] offsets, int[] targets) {

        /** The rows of {@code graph}, or empty when the kernel may not answer it (see the class comment). */
        static <N> Optional<Rows<N>> of(Graph<N> graph) {
            List<N> order = new ArrayList<>(graph.nodes());
            Map<N, Integer> position = HashMap.newHashMap(order.size());
            for (int i = 0; i < order.size(); i++) {
                N node = order.get(i);
                if (!lawful(node)) {
                    return Optional.empty();
                }
                position.put(node, i);
            }
            for (GraphEdge<N> edge : graph.edges()) {
                if (order.get(position.get(edge.from())) != edge.from()
                        || order.get(position.get(edge.to())) != edge.to()) {
                    return Optional.empty();
                }
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
            return Optional.of(new Rows<>(order, offsets, targets));
        }
    }
}
