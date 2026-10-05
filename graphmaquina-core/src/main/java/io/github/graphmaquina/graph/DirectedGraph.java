package io.github.graphmaquina.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable directed graph whose edges carry a label, generic over nodes and labels. Unlike {@link Graph},
 * which tracks bare topology, this type keeps every declared edge — including parallel edges between the
 * same pair of nodes and self-loops — and indexes them for direct lookup by origin or destination.
 *
 * <p>Nodes and edges keep their declared order. Construction rejects {@code null} nodes and labels,
 * duplicate nodes, duplicate identical edges, and edges whose endpoints are not among the declared nodes.
 * It does not reject two distinct edges that share the same origin and label with different destinations;
 * that constraint belongs to whatever machine model is built on top of this graph.
 *
 * @param <N> node type
 * @param <L> edge label type
 */
public final class DirectedGraph<N, L> {

    private final List<N> nodes;
    private final List<DirectedEdge<N, L>> edges;
    private final Map<N, List<DirectedEdge<N, L>>> outgoing;
    private final Map<N, List<DirectedEdge<N, L>>> incoming;
    private final Graph<N> topology;

    private DirectedGraph(List<N> nodes, List<DirectedEdge<N, L>> edges,
            Map<N, List<DirectedEdge<N, L>>> outgoing, Map<N, List<DirectedEdge<N, L>>> incoming,
            Graph<N> topology) {
        this.nodes = nodes;
        this.edges = edges;
        this.outgoing = outgoing;
        this.incoming = incoming;
        this.topology = topology;
    }

    /**
     * Builds a graph from its declared nodes and labeled edges, in a few linear passes over each. Rejects
     * {@code null} nodes with {@link NullPointerException}, and duplicate nodes, duplicate identical edges and
     * edges whose endpoints are not among the declared nodes with {@link IllegalArgumentException}.
     */
    public static <N, L> DirectedGraph<N, L> of(List<N> nodes, List<DirectedEdge<N, L>> edges) {
        Map<N,List<DirectedEdge<N,L>>> outgoing = new LinkedHashMap<>();
        Map<N,List<DirectedEdge<N,L>>> incoming = new LinkedHashMap<>();
        for (N node : nodes) {
            if (outgoing.putIfAbsent(Objects.requireNonNull(node), new ArrayList<>()) != null)
                throw new IllegalArgumentException("duplicate node: " + node);
            incoming.put(node, new ArrayList<>());
        }
        Set<DirectedEdge<N,L>> seen = new HashSet<>();
        for (DirectedEdge<N,L> edge : edges) {
            if (!outgoing.containsKey(edge.from()) || !incoming.containsKey(edge.to()))
                throw new IllegalArgumentException("edge endpoint outside declared nodes: " + edge);
            if (!seen.add(edge)) throw new IllegalArgumentException("duplicate edge: " + edge);
            outgoing.get(edge.from()).add(edge);
            incoming.get(edge.to()).add(edge);
        }

        List<GraphEdge<N>> topologyEdges = new ArrayList<>();
        for (DirectedEdge<N, L> edge : edges) {
            topologyEdges.add(new GraphEdge<>(edge.from(), edge.to()));
        }
        return new DirectedGraph<>(List.copyOf(nodes), List.copyOf(edges),
                freeze(outgoing), freeze(incoming), Graph.of(nodes, topologyEdges));
    }

    private static <N, L> Map<N, List<DirectedEdge<N, L>>> freeze(Map<N, List<DirectedEdge<N, L>>> assembled) {
        Map<N, List<DirectedEdge<N, L>>> frozen = new LinkedHashMap<>();
        for (Map.Entry<N, List<DirectedEdge<N, L>>> entry : assembled.entrySet()) {
            frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Collections.unmodifiableMap(frozen);
    }

    public List<N> nodes() {
        return nodes;
    }

    public List<DirectedEdge<N, L>> edges() {
        return edges;
    }

    /** Edges leaving {@code node}, in declaration order. */
    public List<DirectedEdge<N, L>> outgoing(N node) {
        return requireIndexed(outgoing, node);
    }

    /** Edges arriving at {@code node}, in declaration order. */
    public List<DirectedEdge<N, L>> incoming(N node) {
        return requireIndexed(incoming, node);
    }

    private List<DirectedEdge<N, L>> requireIndexed(Map<N, List<DirectedEdge<N, L>>> index, N node) {
        List<DirectedEdge<N, L>> found = index.get(node);
        if (found == null) {
            throw new IllegalArgumentException("unknown node: " + node);
        }
        return found;
    }

    /**
     * Projects this graph onto the existing {@link Graph}: same nodes, with parallel edges between the
     * same pair of endpoints collapsed to one topological edge.
     */
    public Graph<N> topology() {
        return topology;
    }

    @Override
    public String toString() {
        return "DirectedGraph" + edges;
    }
}
