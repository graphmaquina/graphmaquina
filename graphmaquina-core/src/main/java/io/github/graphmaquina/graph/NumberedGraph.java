package io.github.graphmaquina.graph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A {@link Graph} numbered once, so that {@link StrongComponents#find} and {@link Graph#cycles()} walk arrays of ints
 * instead of hashing a node at every step. Node {@code i} is the i-th declared node. The edges become compressed rows
 * of those numbers twice: forward, in the order {@link Graph#successors} lists each node's successors, and backward,
 * in the order of {@link Graph#edges()}. Every entry keeps the very instance its edge carries, which may be equal to
 * the declared node without being it, and the searches below follow the object searches step for step, so they
 * return the same lists holding the same instances.
 *
 * <p>Only a graph whose nodes and edge endpoints are all strings, enum constants or boxed primitives is numbered.
 * Their {@code equals} and {@code hashCode} are final JDK code that keeps its contract and runs nothing of the user's,
 * so how often the numbering calls them is not observable. Any other graph is left to the object searches, so a
 * user's {@code equals} and {@code hashCode} run exactly as those searches call them.
 */
final class NumberedGraph<N> {

    private final Object[] nodes;
    private final int[] forwardStart;
    private final int[] forwardTarget;
    private final Object[] forwardInstance;
    private final int[] backwardStart;
    private final int[] backwardSource;
    private final Object[] backwardInstance;

    private NumberedGraph(Object[] nodes, int[] from, int[] to, Object[] fromInstance, Object[] toInstance) {
        this.nodes = nodes;
        this.forwardStart = starts(nodes.length, from);
        this.forwardTarget = new int[from.length];
        this.forwardInstance = new Object[from.length];
        fill(forwardStart, from, to, toInstance, forwardTarget, forwardInstance);
        this.backwardStart = starts(nodes.length, to);
        this.backwardSource = new int[from.length];
        this.backwardInstance = new Object[from.length];
        fill(backwardStart, to, from, fromInstance, backwardSource, backwardInstance);
    }

    /** The numbered form of {@code graph}, or empty when a node or an edge endpoint has user-defined equality. */
    static <N> Optional<NumberedGraph<N>> of(Graph<N> graph) {
        // Checked before anything is copied, so a graph of user nodes, which keeps the object search, pays only this.
        for (N node : graph.nodes()) {
            if (!lawful(node)) {
                return Optional.empty();
            }
        }
        Object[] nodes = graph.nodes().toArray();
        Set<GraphEdge<N>> edges = graph.edges();
        int[] from = new int[edges.size()];
        int[] to = new int[edges.size()];
        Object[] fromInstance = new Object[edges.size()];
        Object[] toInstance = new Object[edges.size()];
        int edge = 0;
        for (GraphEdge<N> declared : edges) {
            if (!lawful(declared.from()) || !lawful(declared.to())) {
                return Optional.empty();
            }
            from[edge] = graph.declarationIndex(declared.from());
            to[edge] = graph.declarationIndex(declared.to());
            fromInstance[edge] = declared.from();
            toInstance[edge] = declared.to();
            edge++;
        }
        return Optional.of(new NumberedGraph<>(nodes, from, to, fromInstance, toInstance));
    }

    /** A final JDK type whose {@code equals} and {@code hashCode} keep their contract and run no user code. */
    private static boolean lawful(Object node) {
        return node instanceof String || node instanceof Enum<?> || node instanceof Integer || node instanceof Long
                || node instanceof Short || node instanceof Byte || node instanceof Character
                || node instanceof Boolean;
    }

    /** Where the row of each node starts in an array of edges sorted by {@code keys}, plus the end of the last. */
    private static int[] starts(int count, int[] keys) {
        int[] start = new int[count + 1];
        for (int key : keys) {
            start[key + 1]++;
        }
        for (int node = 0; node < count; node++) {
            start[node + 1] += start[node];
        }
        return start;
    }

    /** Sorts the edges into rows by {@code keys}, keeping their order within a row (a stable counting sort). */
    private static void fill(int[] start, int[] keys, int[] others, Object[] instances, int[] targets,
                             Object[] targetInstances) {
        int[] next = Arrays.copyOf(start, start.length - 1);
        for (int edge = 0; edge < keys.length; edge++) {
            int slot = next[keys[edge]]++;
            targets[slot] = others[edge];
            targetInstances[slot] = instances[edge];
        }
    }

    /**
     * What {@link StrongComponents#find} returns, by the same two passes: a depth-first pass over the successors
     * records the order in which nodes finish, then a pass over the edges backwards, from the last node to finish,
     * gathers each component. Each node keeps the instance with which the second pass first reached it.
     */
    List<Set<N>> components() {
        Object[] reached = new Object[nodes.length];
        int[] finished = finishOrder(reached);
        Object[] chosen = new Object[nodes.length];
        int[] component = new int[nodes.length];
        int count = gather(finished, reached, chosen, component);
        return freeze(component, count, chosen);
    }

    /**
     * The nodes in the order the depth-first pass over the successors finishes them, roots taken in declared order;
     * {@code reached} records the instance with which the pass first reached each node.
     */
    private int[] finishOrder(Object[] reached) {
        int[] finished = new int[nodes.length];
        int done = 0;
        int[] stackNode = new int[nodes.length];
        int[] stackEdge = new int[nodes.length];
        for (int root = 0; root < nodes.length; root++) {
            if (reached[root] != null) {
                continue;
            }
            reached[root] = nodes[root];
            stackNode[0] = root;
            stackEdge[0] = forwardStart[root];
            int depth = 1;
            while (depth > 0) {
                int node = stackNode[depth - 1];
                int edge = stackEdge[depth - 1];
                if (edge == forwardStart[node + 1]) {
                    depth--;
                    finished[done++] = node;
                    continue;
                }
                stackEdge[depth - 1] = edge + 1;
                int next = forwardTarget[edge];
                if (reached[next] == null) {
                    reached[next] = forwardInstance[edge];
                    stackNode[depth] = next;
                    stackEdge[depth] = forwardStart[next];
                    depth++;
                }
            }
        }
        return finished;
    }

    /**
     * Walks the edges backwards from each node not yet gathered, last finished first, taking pending nodes last in
     * first out; writes each node's component, numbered in the order they are gathered, and the instance it is
     * gathered with into {@code chosen}. Returns how many components there are.
     */
    private int gather(int[] finished, Object[] reached, Object[] chosen, int[] component) {
        int[] pending = new int[nodes.length];
        int count = 0;
        for (int i = finished.length - 1; i >= 0; i--) {
            int root = finished[i];
            if (chosen[root] != null) {
                continue;
            }
            chosen[root] = reached[root];
            pending[0] = root;
            int top = 1;
            while (top > 0) {
                int current = pending[--top];
                component[current] = count;
                for (int edge = backwardStart[current]; edge < backwardStart[current + 1]; edge++) {
                    int previous = backwardSource[edge];
                    if (chosen[previous] == null) {
                        chosen[previous] = backwardInstance[edge];
                        pending[top++] = previous;
                    }
                }
            }
            count++;
        }
        return count;
    }

    /**
     * The components ordered by their first declared member, each holding its members in declared order, in the
     * containers {@link StrongComponents#find} returns.
     */
    private List<Set<N>> freeze(int[] component, int count, Object[] chosen) {
        int[] rank = new int[count];
        Arrays.fill(rank, -1);
        int ranked = 0;
        int[] start = new int[count + 1];
        for (int node = 0; node < nodes.length; node++) {
            if (rank[component[node]] < 0) {
                rank[component[node]] = ranked++;
            }
            start[rank[component[node]] + 1]++;
        }
        for (int i = 0; i < count; i++) {
            start[i + 1] += start[i];
        }
        Object[] members = new Object[nodes.length];
        int[] next = Arrays.copyOf(start, count);
        for (int node = 0; node < nodes.length; node++) {
            members[next[rank[component[node]]]++] = chosen[node];
        }
        List<N> grouped = asList(members);
        List<Set<N>> frozen = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            frozen.add(Collections.unmodifiableSet(new LinkedHashSet<>(grouped.subList(start[i], start[i + 1]))));
        }
        return List.copyOf(frozen);
    }

    /**
     * What {@link Graph#cycles()} returns: the components decide which nodes can close a cycle, then a depth-first
     * search from each such root, in declared order, follows only members of the root's component declared at or
     * after it, and lists the path each time it comes back to the root. A path holds the root's declared instance
     * and then the instance each edge it followed carries.
     */
    List<List<N>> cycles() {
        Object[] reached = new Object[nodes.length];
        int[] finished = finishOrder(reached);
        int[] component = new int[nodes.length];
        int count = gather(finished, reached, new Object[nodes.length], component);
        boolean[] closes = closing(component, count);
        List<List<N>> cycles = new ArrayList<>();
        Search search = new Search(component);
        for (int root = 0; root < nodes.length; root++) {
            if (closes[component[root]]) {
                search.from(root, cycles);
            }
        }
        return List.copyOf(cycles);
    }

    /** Whether each component can close a cycle: it has more than one node, or its only node points at itself. */
    private boolean[] closing(int[] component, int count) {
        int[] size = new int[count];
        for (int owner : component) {
            size[owner]++;
        }
        boolean[] closes = new boolean[count];
        for (int node = 0; node < nodes.length; node++) {
            closes[component[node]] = size[component[node]] > 1 || pointsAtItself(node);
        }
        return closes;
    }

    private boolean pointsAtItself(int node) {
        for (int edge = forwardStart[node]; edge < forwardStart[node + 1]; edge++) {
            if (forwardTarget[edge] == node) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static <N> List<N> asList(Object[] instances) {
        return (List<N>) Arrays.asList(instances);
    }

    /** {@code List.of} over a fresh array: the same list class for each size as {@code List.copyOf} of a list. */
    @SuppressWarnings("unchecked")
    private static <N> List<N> listOf(Object[] instances) {
        return List.of((N[]) instances);
    }

    /** The cycle search's path, its stack and its marks, allocated once and reused from root to root. */
    private final class Search {

        private final int[] component;
        private final Object[] path = new Object[nodes.length];
        private final boolean[] onPath = new boolean[nodes.length];
        private final int[] stackNode = new int[nodes.length];
        private final int[] stackEdge = new int[nodes.length];

        private Search(int[] component) {
            this.component = component;
        }

        /**
         * Appends the cycles that start at {@code root}, in the order the search closes them. The path and the stack
         * always have the same depth: the path holds the instance of each node on the stack.
         */
        private void from(int root, List<List<N>> cycles) {
            int rootComponent = component[root];
            path[0] = nodes[root];
            onPath[root] = true;
            stackNode[0] = root;
            stackEdge[0] = forwardStart[root];
            int depth = 1;
            while (depth > 0) {
                int node = stackNode[depth - 1];
                int edge = stackEdge[depth - 1];
                if (edge == forwardStart[node + 1]) {
                    depth--;
                    onPath[node] = false;
                    continue;
                }
                stackEdge[depth - 1] = edge + 1;
                int next = forwardTarget[edge];
                if (component[next] != rootComponent || next < root) {
                    continue;
                }
                if (next == root) {
                    cycles.add(listOf(Arrays.copyOf(path, depth)));
                } else if (!onPath[next]) {
                    onPath[next] = true;
                    path[depth] = forwardInstance[edge];
                    stackNode[depth] = next;
                    stackEdge[depth] = forwardStart[next];
                    depth++;
                }
            }
        }
    }
}
