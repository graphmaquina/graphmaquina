package io.github.graphmaquina.test;

import io.github.graphmaquina.graph.Graph;
import io.github.graphmaquina.test.internal.Checks;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Chainable, typed assertion over the reachability graph of a definition, obtained from
 * {@link GraphAssert#topology()}. Its nodes are all the declared states and its edges are the declared
 * edges, with parallel identifiers between the same pair collapsed into one; a declared self-loop is a
 * self-loop here. Every expectation reports the graph's own answer rather than a reinterpretation of it:
 * reachability lists the origin only when some cycle leads back to it, a node always reaches itself in
 * zero steps, and cycles keep the order and the canonical rotation the graph produces.
 *
 * @param <S> state enum of the machine
 */
public final class TopologyAssert<S extends Enum<S>> {

    private final String context;
    private final Graph<S> actual;

    TopologyAssert(String context, Graph<S> actual) {
        this.context = context;
        this.actual = actual;
    }

    /** The nodes are every declared state, whether or not any edge reaches them. */
    public TopologyAssert<S> hasNodesExactly(Set<S> expected) {
        Objects.requireNonNull(expected, "expected");
        Set<S> wanted = Set.copyOf(expected);
        Graph<S> graph = Checks.subject(context, "nodes", wanted, actual);
        Checks.equal(context, "nodes", wanted, graph.nodes());
        return this;
    }

    /** The destinations reachable in one step, so a state with no outgoing edge has none. */
    public TopologyAssert<S> hasSuccessorsExactly(S from, Set<S> expected) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(expected, "expected");
        Set<S> wanted = Set.copyOf(expected);
        String where = context + " " + from.name();
        Graph<S> graph = Checks.subject(where, "successors", wanted, actual);
        requireNode(graph, from);
        Checks.equal(where, "successors", wanted, graph.successors(from));
        return this;
    }

    /** Everything reachable in one step or more. The origin belongs there only if a cycle leads back to it. */
    public TopologyAssert<S> hasReachableExactly(S origin, Set<S> expected) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(expected, "expected");
        Set<S> wanted = Set.copyOf(expected);
        String where = context + " " + origin.name();
        Graph<S> graph = Checks.subject(where, "reachable", wanted, actual);
        requireNode(graph, origin);
        Checks.equal(where, "reachable", wanted, graph.reachableFrom(origin));
        return this;
    }

    /**
     * Every adjacent pair of the walk must be an edge, and the first step that is not one is reported by
     * its own index. A single-node path has no pair to check, so it holds for any node of the graph. A node
     * the graph does not have, anywhere in the walk, and an empty path are argument errors, not assertion
     * failures.
     */
    public TopologyAssert<S> hasPath(List<S> path) {
        Objects.requireNonNull(path, "path");
        List<S> walk = List.copyOf(path);
        if (walk.isEmpty()) {
            throw new IllegalArgumentException("path must not be empty");
        }
        Graph<S> graph = Checks.subject(context, "path", walk, actual);
        for (S node : walk) {
            requireNode(graph, node);
        }
        for (int index = 1; index < walk.size(); index++) {
            S from = walk.get(index - 1);
            S to = walk.get(index);
            Checks.equal(context, "path[" + index + "]", Boolean.TRUE, graph.successors(from).contains(to));
        }
        return this;
    }

    /**
     * The exact negation of {@link #hasShortestPath}, so it never holds for {@code from == to}: the graph's
     * shortest walk from a node to itself is the single node. Whether a state can come back to itself is
     * answered by {@link #hasReachableExactly}, whose reachable set excludes the origin unless a cycle
     * leads back to it.
     */
    public TopologyAssert<S> hasNoPath(S from, S to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Optional<List<S>> none = Optional.empty();
        String where = where(from, to);
        Graph<S> graph = Checks.subject(where, "path", none, actual);
        requireNode(graph, from);
        requireNode(graph, to);
        Checks.equal(where, "path", none, graph.shortestPath(from, to));
        return this;
    }

    /** The shortest walk the graph finds, which is the single node {@code [from]} when {@code from == to}. */
    public TopologyAssert<S> hasShortestPath(S from, S to, List<S> expected) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(expected, "expected");
        Optional<List<S>> wanted = Optional.of(List.copyOf(expected));
        String where = where(from, to);
        Graph<S> graph = Checks.subject(where, "shortest path", wanted, actual);
        requireNode(graph, from);
        requireNode(graph, to);
        Checks.equal(where, "shortest path", wanted, graph.shortestPath(from, to));
        return this;
    }

    public TopologyAssert<S> hasNoCycles() {
        List<List<S>> none = List.of();
        Graph<S> graph = Checks.subject(context, "cycles", none, actual);
        Checks.equal(context, "cycles", none, graph.cycles());
        return this;
    }

    /**
     * Elementary cycles in the graph's own order and rotation: each one starts at its
     * lowest-declaration-index node and does not repeat the closing node.
     */
    public TopologyAssert<S> hasCyclesExactly(List<List<S>> expected) {
        Objects.requireNonNull(expected, "expected");
        List<List<S>> wanted = expected.stream().map(List::copyOf).toList();
        Graph<S> graph = Checks.subject(context, "cycles", wanted, actual);
        Checks.equal(context, "cycles", wanted, graph.cycles());
        return this;
    }

    private String where(S from, S to) {
        return context + " " + from.name() + " -> " + to.name();
    }

    private static <S extends Enum<S>> void requireNode(Graph<S> graph, S node) {
        if (!graph.nodes().contains(node)) {
            throw new IllegalArgumentException("unknown node: " + node);
        }
    }
}
