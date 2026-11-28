package io.github.graphmaquina.graph;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Supplier;

/**
 * Renders as text everything a caller can observe of the graph operations on one {@link GraphCase}: each answer with
 * the class of every container it returns and the tag of every instance it holds, each refusal with its message,
 * and after each operation the {@code equals}/{@code hashCode} calls it made on user nodes, in order. Rendering
 * itself reads only identities and classes, so it adds no call to the log.
 */
final class GraphObservations {

    private GraphObservations() { }

    /** The SHA-256 of the observations of every case, in order, as lowercase hex. */
    static String digest(List<? extends GraphCase<?>> cases) {
        MessageDigest digest = sha256();
        for (GraphCase<?> graphCase : cases) {
            digest.update(of(graphCase).getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static <N> String of(GraphCase<N> graphCase) {
        StringBuilder out = new StringBuilder("built calls ").append(graphCase.construction()).append('\n');
        Graph<N> graph = graphCase.graph();
        step(out, graphCase, "find", () -> list(graphCase, StrongComponents.find(graph)));
        step(out, graphCase, "cycles", () -> list(graphCase, graph.cycles()));
        List<Set<N>> components = componentsOrNone(graph);
        graphCase.log().setLength(0);
        for (Set<N> component : components) {
            step(out, graphCase, "witness", () -> container(graphCase,
                    StrongComponents.cycleWitness(graph, component)));
        }
        N first = graphCase.declared().getFirst();
        N last = graphCase.declared().getLast();
        step(out, graphCase, "reachable", () -> container(graphCase, graph.reachableFrom(first)));
        step(out, graphCase, "path", () -> path(graphCase, graph.shortestPath(first, last)));
        step(out, graphCase, "induced", () -> graph(graphCase, graph.inducedSubgraph(everyOther(graph.nodes()))));
        return out.toString();
    }

    /**
     * Runs one operation and renders its answer, or the class and message of what it threw, then the calls it made
     * on user nodes; the log is emptied for the next operation.
     */
    private static void step(StringBuilder out, GraphCase<?> graphCase, String name, Supplier<String> operation) {
        String observed;
        try {
            observed = operation.get();
        } catch (RuntimeException thrown) {
            observed = "threw " + thrown.getClass().getName() + ": " + thrown.getMessage();
        }
        out.append(name).append(' ').append(observed).append(" calls ").append(graphCase.log()).append('\n');
        graphCase.log().setLength(0);
    }

    /**
     * The components to ask a witness of, found again; none when finding them throws, which the find step already
     * records. The caller drops the calls this second search makes: they repeat those of the find step.
     */
    private static <N> List<Set<N>> componentsOrNone(Graph<N> graph) {
        try {
            return StrongComponents.find(graph);
        } catch (RuntimeException thrown) {
            return List.of();
        }
    }

    private static String path(GraphCase<?> graphCase, Optional<? extends List<?>> path) {
        return path.isEmpty() ? "empty" : container(graphCase, path.get());
    }

    private static String graph(GraphCase<?> graphCase, Graph<?> graph) {
        StringJoiner edges = new StringJoiner(",", graph.edges().getClass().getName() + "[", "]");
        for (GraphEdge<?> edge : graph.edges()) {
            edges.add(tag(graphCase, edge.from()) + ">" + tag(graphCase, edge.to()));
        }
        return container(graphCase, graph.nodes()) + " " + edges;
    }

    /** A list of containers: its own class, then each element rendered as a container. */
    private static String list(GraphCase<?> graphCase, List<? extends Collection<?>> containers) {
        StringJoiner joined = new StringJoiner(",", containers.getClass().getName() + "[", "]");
        for (Collection<?> container : containers) {
            joined.add(container(graphCase, container));
        }
        return joined.toString();
    }

    private static String container(GraphCase<?> graphCase, Collection<?> container) {
        StringJoiner joined = new StringJoiner(",", container.getClass().getName() + "[", "]");
        for (Object element : container) {
            joined.add(tag(graphCase, element));
        }
        return joined.toString();
    }

    private static String tag(GraphCase<?> graphCase, Object instance) {
        String tag = graphCase.tags().get(instance);
        return tag == null ? "?" + instance : tag;
    }

    private static <N> List<N> everyOther(Set<N> nodes) {
        List<N> kept = new ArrayList<>();
        int position = 0;
        for (N node : nodes) {
            if (position++ % 2 == 0) {
                kept.add(node);
            }
        }
        return kept;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException absent) {
            throw new IllegalStateException(absent);
        }
    }
}
