package io.github.graphmaquina.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.NonNull;

/**
 * Strongly connected components of a {@link Graph}, computed iteratively so that a graph with tens of thousands
 * of nodes never depends on the Java stack. Used to authorise cycles without enumerating them: a component of
 * more than one node, or a single node with a self-loop, is a cycle.
 *
 * <p>Both the components and the nodes inside each of them follow the graph's declared node order, so a build
 * reports the same components and the same witnesses every time.
 */
public final class StrongComponents {

    private StrongComponents() { }

    /** The components of {@code graph}, ordered by the declaration index of their first node. */
    public static <N> List<Set<N>> find(@NonNull Graph<N> graph) {
        List<N> order = new ArrayList<>(graph.nodes());
        Map<N, Integer> index = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            index.put(order.get(i), i);
        }
        Map<N, List<N>> reverse = reverseAdjacency(graph, order);
        List<N> byFinish = finishOrder(graph, order);
        Set<N> assigned = new LinkedHashSet<>();
        List<List<N>> components = new ArrayList<>();
        for (int i = byFinish.size() - 1; i >= 0; i--) {
            N root = byFinish.get(i);
            if (assigned.contains(root)) {
                continue;
            }
            List<N> component = new ArrayList<>();
            Deque<N> pending = new ArrayDeque<>();
            pending.addLast(root);
            assigned.add(root);
            while (!pending.isEmpty()) {
                N current = pending.removeLast();
                component.add(current);
                for (N previous : reverse.get(current)) {
                    if (assigned.add(previous)) {
                        pending.addLast(previous);
                    }
                }
            }
            component.sort(Comparator.comparingInt(index::get));
            components.add(component);
        }
        components.sort(Comparator.comparingInt((List<N> component) -> index.get(component.get(0))));
        List<Set<N>> frozen = new ArrayList<>();
        for (List<N> component : components) {
            frozen.add(Collections.unmodifiableSet(new LinkedHashSet<>(component)));
        }
        return List.copyOf(frozen);
    }

    /**
     * One cycle inside {@code component}. The search starts at the member the graph declares first and stops at
     * the first back edge it meets, so the list starts and ends on the node that back edge closes, which is not
     * always the first member: over {@code A→B, B→C, C→B, C→A} the witness of {@code {A, B, C}} is
     * {@code [B, C, B]}. A self-loop reads {@code [A, A]}. When nothing that member reaches inside the set closes
     * a cycle, which only a set that is not one strongly connected component allows, the search goes on from the
     * next member not yet visited, in declared order: over {@code B→C, C→B} alone, the witness of
     * {@code {A, B, C}} is {@code [B, C, B]}. The answer follows the graph's declared order and its successor order
     * and never the iteration order of {@code component}, so an unordered set and an ordered one yield the same
     * witness.
     *
     * <p>Membership is decided by the graph: every element of {@code component} must be one of
     * {@code graph.nodes()} under the node type's {@code equals} and {@code hashCode}. The component's own
     * {@code contains} is never consulted, so a {@code Set} whose membership disagrees with {@code equals} is
     * judged by {@code equals}, in both directions. A case-insensitive {@code TreeSet} holding {@code "a"} for the
     * graph node {@code "A"} is rejected as holding a node outside the graph. An identity-based set holding an equal
     * but distinct instance of a graph node is accepted, and the witness uses the graph's declared instance. A
     * {@code contains} that would throw, such as the natural order of a {@code TreeSet} over a graph whose nodes are
     * not mutually comparable, is never reached.
     *
     * @throws IllegalArgumentException if the component is empty, holds no cycle, or is not part of {@code graph}
     */
    public static <N> List<N> cycleWitness(@NonNull Graph<N> graph, @NonNull Set<N> component) {
        if (component.isEmpty()) {
            throw new IllegalArgumentException("an empty component holds no cycle");
        }
        Set<N> members = new HashSet<>();
        List<Member<N>> roots = roots(graph, component, members);
        Map<N, N> parent = new HashMap<>();
        Set<N> done = new LinkedHashSet<>();
        for (Member<N> root : roots) {
            N start = root.node();
            if (done.contains(start)) {
                continue;
            }
            Optional<List<N>> witness = witnessFrom(graph, start, members, parent, done);
            if (witness.isPresent()) {
                return witness.get();
            }
        }
        throw new IllegalArgumentException("the component " + component + " holds no cycle");
    }

    /**
     * The members of {@code component}, each as the graph's own instance with its position in the declared order,
     * sorted by that position; each one also joins {@code members}. A node outside the graph is refused.
     */
    private static <N> List<Member<N>> roots(Graph<N> graph, Set<N> component, Set<N> members) {
        List<Member<N>> roots = new ArrayList<>();
        for (N node : component) {
            if (!graph.nodes().contains(node)) {
                throw new IllegalArgumentException("the component " + component + " holds a node outside the graph");
            }
            members.add(node);
            roots.add(new Member<>(graph.declarationIndex(node), graph.declaredNode(node)));
        }
        roots.sort(Comparator.comparingInt((Member<N> member) -> member.position()));
        return roots;
    }

    /**
     * The depth-first search of {@link #cycleWitness} from {@code start} through {@code members}: the cycle that the
     * first back edge it meets closes, or empty when it finishes every node it reaches without meeting one. It
     * records in {@code parent} the node each one was reached from, and adds each node it finishes to {@code done},
     * which a later search skips.
     */
    private static <N> Optional<List<N>> witnessFrom(Graph<N> graph, N start, Set<N> members, Map<N, N> parent,
                                                     Set<N> done) {
        Set<N> onPath = new LinkedHashSet<>();
        Deque<N> stack = new ArrayDeque<>();
        Deque<List<N>> successors = new ArrayDeque<>();
        Deque<int[]> cursors = new ArrayDeque<>();
        push(graph, start, members, stack, successors, cursors, onPath);
        while (!stack.isEmpty()) {
            N current = stack.peekLast();
            List<N> next = successors.peekLast();
            int[] cursor = cursors.peekLast();
            if (cursor[0] == next.size()) {
                stack.removeLast();
                successors.removeLast();
                cursors.removeLast();
                onPath.remove(current);
                done.add(current);
                continue;
            }
            N candidate = next.get(cursor[0]);
            cursor[0]++;
            if (onPath.contains(candidate)) {
                return Optional.of(close(parent, current, candidate));
            }
            if (done.contains(candidate)) {
                continue;
            }
            parent.put(candidate, current);
            push(graph, candidate, members, stack, successors, cursors, onPath);
        }
        return Optional.empty();
    }

    /**
     * Puts {@code node} at the end of the search's path: on {@code stack}, with its successors inside
     * {@code members} and a cursor at the first of them, and in {@code onPath}.
     */
    private static <N> void push(Graph<N> graph, N node, Set<N> members, Deque<N> stack, Deque<List<N>> successors,
                                 Deque<int[]> cursors, Set<N> onPath) {
        stack.addLast(node);
        successors.addLast(inside(graph, node, members));
        cursors.addLast(new int[] {0});
        onPath.add(node);
    }

    /** A member of the searched set: the graph's own instance of it, and its position in the declared order. */
    private record Member<N>(int position, N node) { }

    /** Walks the parents from {@code last} back to {@code first} and closes the list on {@code first}. */
    private static <N> List<N> close(Map<N, N> parent, N last, N first) {
        List<N> reversed = new ArrayList<>();
        N cursor = last;
        reversed.add(cursor);
        while (!cursor.equals(first)) {
            cursor = parent.get(cursor);
            reversed.add(cursor);
        }
        List<N> witness = new ArrayList<>();
        for (int i = reversed.size() - 1; i >= 0; i--) {
            witness.add(reversed.get(i));
        }
        witness.add(first);
        return List.copyOf(witness);
    }

    private static <N> List<N> inside(Graph<N> graph, N node, Set<N> members) {
        List<N> kept = new ArrayList<>();
        for (N successor : graph.successors(node)) {
            if (members.contains(successor)) {
                kept.add(successor);
            }
        }
        return kept;
    }

    /** Built once: the second traversal walks the edges backwards and must not rescan the whole graph per node. */
    private static <N> Map<N, List<N>> reverseAdjacency(Graph<N> graph, List<N> order) {
        Map<N, List<N>> reverse = new LinkedHashMap<>();
        for (N node : order) {
            reverse.put(node, new ArrayList<>());
        }
        for (GraphEdge<N> edge : graph.edges()) {
            reverse.get(edge.to()).add(edge.from());
        }
        return reverse;
    }

    /** Iterative depth-first pass over the forward edges, recording each node once it has no successors left. */
    private static <N> List<N> finishOrder(Graph<N> graph, List<N> order) {
        Set<N> seen = new LinkedHashSet<>();
        List<N> finished = new ArrayList<>();
        Deque<N> stack = new ArrayDeque<>();
        Deque<List<N>> successors = new ArrayDeque<>();
        Deque<int[]> cursors = new ArrayDeque<>();
        for (N root : order) {
            if (!seen.add(root)) {
                continue;
            }
            stack.addLast(root);
            successors.addLast(new ArrayList<>(graph.successors(root)));
            cursors.addLast(new int[] {0});
            while (!stack.isEmpty()) {
                N current = stack.peekLast();
                List<N> next = successors.peekLast();
                int[] cursor = cursors.peekLast();
                if (cursor[0] == next.size()) {
                    stack.removeLast();
                    successors.removeLast();
                    cursors.removeLast();
                    finished.add(current);
                    continue;
                }
                N candidate = next.get(cursor[0]);
                cursor[0]++;
                if (seen.add(candidate)) {
                    stack.addLast(candidate);
                    successors.addLast(new ArrayList<>(graph.successors(candidate)));
                    cursors.addLast(new int[] {0});
                }
            }
        }
        return finished;
    }
}
