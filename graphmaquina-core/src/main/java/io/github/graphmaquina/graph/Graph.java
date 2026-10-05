package io.github.graphmaquina.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.NonNull;

/** Immutable directed graph, generic over nodes, none of them null. Works equally well with enums and names. */
public final class Graph<N> {

    private final Set<N> nodes;
    private final Set<GraphEdge<N>> edges;
    private final Map<N, Set<N>> successors;
    private final Map<N, Declaration<N>> declarationIndex;

    private Graph(Set<N> nodes, Set<GraphEdge<N>> edges, Map<N, Set<N>> successors) {
        this.nodes = nodes;
        this.edges = edges;
        this.successors = successors;
        Map<N, Declaration<N>> positions = HashMap.newHashMap(nodes.size());
        int position = 0;
        for (N node : nodes) {
            positions.put(node, new Declaration<>(position, node));
            position++;
        }
        this.declarationIndex = positions;
    }

    /**
     * The graph over {@code nodes}, in their declared order with repeats collapsed to the first appearance, and
     * {@code edges}.
     *
     * @throws NullPointerException if a node is {@code null}: no search of this class could walk it
     * @throws IllegalArgumentException if an edge has an endpoint outside the declared nodes
     */
    public static <N> Graph<N> of(@NonNull Collection<? extends N> nodes, @NonNull Collection<? extends GraphEdge<N>> edges) {
        Set<N> declared = new LinkedHashSet<>();
        for (N node : nodes) {
            declared.add(Objects.requireNonNull(node, "node"));
        }
        Set<N> nodeSet = Collections.unmodifiableSet(declared);
        Map<N, Set<N>> mutableSuccessors = new LinkedHashMap<>();
        for (N node : nodeSet) {
            mutableSuccessors.put(node, new LinkedHashSet<>());
        }
        Set<GraphEdge<N>> edgeSet = new LinkedHashSet<>();
        for (GraphEdge<N> edge : edges) {
            if (!nodeSet.contains(edge.from()) || !nodeSet.contains(edge.to())) {
                throw new IllegalArgumentException(
                        "edge " + edge.from() + " → " + edge.to() + " has an endpoint outside the declared nodes");
            }
            edgeSet.add(edge);
            mutableSuccessors.get(edge.from()).add(edge.to());
        }
        Map<N, Set<N>> frozen = new LinkedHashMap<>();
        for (Map.Entry<N, Set<N>> entry : mutableSuccessors.entrySet()) {
            frozen.put(entry.getKey(), Collections.unmodifiableSet(entry.getValue()));
        }
        return new Graph<>(nodeSet, Collections.unmodifiableSet(edgeSet), Collections.unmodifiableMap(frozen));
    }

    public Set<N> nodes() {
        return nodes;
    }

    public Set<GraphEdge<N>> edges() {
        return edges;
    }

    public Set<N> successors(@NonNull N node) {
        requireNode(node);
        return successors.get(node);
    }

    /**
     * The position of a node in the declared order, counted from zero, keeping the first appearance of a repeated
     * node. The whole map is built once with the graph, so asking which member of a set comes first never costs a
     * walk over every node.
     *
     * @throws IllegalArgumentException if the node is not in this graph
     */
    int declarationIndex(N node) {
        Declaration<N> declaration = declarationIndex.get(node);
        if (declaration == null) {
            throw new IllegalArgumentException("unknown node: " + node);
        }
        return declaration.position();
    }

    /** Declaration-order position of a node and the instance the graph stores for it. */
    private record Declaration<N>(int position, N node) { }

    /** The original declared representative, even when the lookup uses an equal but distinct node. */
    N declaredNode(N node) {
        Declaration<N> declaration = declarationIndex.get(node);
        if (declaration == null) {
            throw new IllegalArgumentException("unknown node: " + node);
        }
        return declaration.node();
    }

    public Set<N> reachableFrom(@NonNull N origin) {
        if (!nodes.contains(origin)) {
            throw new IllegalArgumentException("unknown node: " + origin);
        }
        Set<N> visited = new LinkedHashSet<>();
        Deque<N> queue = new ArrayDeque<>();
        queue.addLast(origin);
        while (!queue.isEmpty()) {
            N current = queue.removeFirst();
            for (N next : successors.get(current)) {
                if (visited.add(next)) {
                    queue.addLast(next);
                }
            }
        }
        return Collections.unmodifiableSet(visited);
    }

    public Optional<List<N>> shortestPath(@NonNull N from, @NonNull N to) {
        if (!nodes.contains(from)) {
            throw new IllegalArgumentException("unknown node: " + from);
        }
        if (!nodes.contains(to)) {
            throw new IllegalArgumentException("unknown node: " + to);
        }
        if (from.equals(to)) {
            return Optional.of(List.of(from));
        }
        Map<N, N> parent = new HashMap<>();
        Set<N> visited = new HashSet<>();
        Deque<N> queue = new ArrayDeque<>();
        visited.add(from);
        queue.addLast(from);
        while (!queue.isEmpty()) {
            N current = queue.removeFirst();
            for (N next : successors.get(current)) {
                if (!visited.add(next)) {
                    continue;
                }
                parent.put(next, current);
                if (next.equals(to)) {
                    return Optional.of(buildPath(parent, from, to));
                }
                queue.addLast(next);
            }
        }
        return Optional.empty();
    }

    private List<N> buildPath(Map<N, N> parent, N from, N to) {
        LinkedList<N> path = new LinkedList<>();
        N cursor = to;
        while (!cursor.equals(from)) {
            path.addFirst(cursor);
            cursor = parent.get(cursor);
        }
        path.addFirst(from);
        return List.copyOf(path);
    }

    /**
     * The subgraph over {@code kept}: those nodes, and only the edges whose two endpoints are both among them.
     * The kept nodes keep the order this graph declares them in, whatever the iteration order of {@code kept}.
     *
     * @throws IllegalArgumentException if a node of {@code kept} is not in this graph
     */
    public Graph<N> inducedSubgraph(@NonNull Collection<? extends N> kept) {
        Set<N> wanted = new HashSet<>();
        for (N node : kept) {
            requireNode(node);
            wanted.add(node);
        }
        Set<N> retained = new LinkedHashSet<>();
        for (N node : nodes) {
            if (wanted.contains(node)) {
                retained.add(node);
            }
        }
        List<GraphEdge<N>> inside = new ArrayList<>();
        for (GraphEdge<N> edge : edges) {
            if (retained.contains(edge.from()) && retained.contains(edge.to())) {
                inside.add(edge);
            }
        }
        return Graph.of(retained, inside);
    }

    /**
     * Elementary cycles. Each starts at its lowest-declaration-index node and does not repeat the closing node.
     * The whole listing is produced: there is no quota, no deadline and no sampling.
     *
     * <p>One pass over the strongly connected components decides, before any path is walked, which nodes can
     * possibly close a cycle: a component of more than one node, or a single node that points at itself. Roots
     * outside those components are skipped and candidates outside the root's own component are never followed,
     * so an acyclic graph costs its own size instead of its number of paths. Inside one large component the
     * search can still walk paths that close nothing; this prunes the components, not the search within one.
     *
     * <p>The search carries its path on an explicit stack, so a cycle as long as the graph does not grow the
     * Java stack. Roots are visited in the declared node order and not component by component, because members
     * of different components interleave in that order and the order of the answer is part of the contract.
     */
    public List<List<N>> cycles() {
        List<Set<N>> components = StrongComponents.find(this);
        Map<N, Integer> componentOf = new HashMap<>();
        Set<Integer> cyclic = new HashSet<>();
        for (int i = 0; i < components.size(); i++) {
            Set<N> component = components.get(i);
            for (N member : component) {
                componentOf.put(member, i);
            }
            boolean closesACycle = component.size() > 1;
            if (!closesACycle) {
                N only = component.iterator().next();
                closesACycle = successors.get(only).contains(only);
            }
            if (closesACycle) {
                cyclic.add(i);
            }
        }
        List<List<N>> cycles = new ArrayList<>();
        for (N root : nodes) {
            // Both reads are constant for the whole search below, so they are taken once here rather than on
            // every candidate: re-deriving them per edge costs two hash lookups each time and buys nothing.
            Integer rootComponent = componentOf.get(root);
            if (!cyclic.contains(rootComponent)) {
                continue;
            }
            int rootIndex = declarationIndex(root);
            List<N> path = new ArrayList<>();
            Set<N> onPath = new HashSet<>();
            Deque<Frame<N>> stack = new ArrayDeque<>();
            path.add(root);
            onPath.add(root);
            stack.addLast(new Frame<>(root, successors.get(root).iterator()));
            while (!stack.isEmpty()) {
                Frame<N> frame = stack.peekLast();
                if (!frame.next().hasNext()) {
                    stack.removeLast();
                    onPath.remove(frame.node());
                    path.remove(path.size() - 1);
                    continue;
                }
                N next = frame.next().next();
                if (!componentOf.get(next).equals(rootComponent)) {
                    continue;
                }
                if (declarationIndex(next) < rootIndex) {
                    continue;
                }
                if (next.equals(root)) {
                    cycles.add(List.copyOf(path));
                } else if (onPath.add(next)) {
                    path.add(next);
                    stack.addLast(new Frame<>(next, successors.get(next).iterator()));
                }
            }
        }
        return List.copyOf(cycles);
    }

    /** One node of the path under construction, together with the successors of that node still to examine. */
    private record Frame<N>(N node, Iterator<N> next) { }

    private void requireNode(N node) {
        if (!nodes.contains(node)) {
            throw new IllegalArgumentException("unknown node: " + node);
        }
    }

    @Override
    public String toString() {
        return "Graph" + edges;
    }
}
