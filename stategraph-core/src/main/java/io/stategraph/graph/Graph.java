package io.stategraph.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.NonNull;

/** Immutable directed graph, generic over nodes. Works equally well with enums and names. */
public final class Graph<N> {

    private final Set<N> nodes;
    private final Set<GraphEdge<N>> edges;
    private final Map<N, Set<N>> successors;

    private Graph(Set<N> nodes, Set<GraphEdge<N>> edges, Map<N, Set<N>> successors) {
        this.nodes = nodes;
        this.edges = edges;
        this.successors = successors;
    }

    public static <N> Graph<N> of(@NonNull Collection<? extends N> nodes, @NonNull Collection<? extends GraphEdge<N>> edges) {
        Set<N> nodeSet = Collections.unmodifiableSet(new LinkedHashSet<N>(nodes));
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

    /** Elementary cycles. Each starts at its lowest-declaration-index node and does not repeat the closing node. */
    public List<List<N>> cycles() {
        List<N> order = new ArrayList<>(nodes);
        Map<N, Integer> index = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            index.put(order.get(i), i);
        }
        List<List<N>> found = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) {
            N start = order.get(i);
            Deque<N> path = new ArrayDeque<>();
            Set<N> onPath = new HashSet<>();
            path.addLast(start);
            onPath.add(start);
            searchCycles(start, start, i, index, path, onPath, found);
        }
        return List.copyOf(found);
    }

    private void searchCycles(N start, N current, int minIndex, Map<N, Integer> index,
                              Deque<N> path, Set<N> onPath, List<List<N>> found) {
        for (N next : successors.get(current)) {
            if (next.equals(start)) {
                found.add(List.copyOf(path));
                continue;
            }
            if (index.get(next) < minIndex || onPath.contains(next)) {
                continue;
            }
            path.addLast(next);
            onPath.add(next);
            searchCycles(start, next, minIndex, index, path, onPath, found);
            path.removeLast();
            onPath.remove(next);
        }
    }

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
