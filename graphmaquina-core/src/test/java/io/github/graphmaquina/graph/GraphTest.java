package io.github.graphmaquina.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link Graph} over string nodes: declaration order, edge endpoints, reachability, shortest paths, cycle enumeration
 * and its dense counts, immutable results, null nodes, induced subgraphs and the declaration index.
 */
class GraphTest {

    private static Graph<String> graph(List<String> nodes, String... edges) {
        List<GraphEdge<String>> parsed = java.util.Arrays.stream(edges)
                .map((String edge) -> edge.split(">"))
                .map((String[] parts) -> new GraphEdge<>(parts[0], parts[1]))
                .toList();
        return Graph.of(nodes, parsed);
    }

    @Test
    void keepsDeclarationOrder() {
        Graph<String> g = graph(List.of("C", "A", "B"), "C>A", "A>B");
        assertEquals(List.of("C", "A", "B"), List.copyOf(g.nodes()));
        assertEquals(List.of(new GraphEdge<>("C", "A"), new GraphEdge<>("A", "B")), List.copyOf(g.edges()));
    }

    @Test
    void rejectsEdgeWithUnknownEndpoint() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> graph(List.of("A"), "A>Z"));
        assertTrue(error.getMessage().contains("A → Z"));
    }

    @Test
    void successorsOfUnknownNodeThrows() {
        Graph<String> g = graph(List.of("A"));
        assertThrows(IllegalArgumentException.class, () -> g.successors("Z"));
    }

    @Test
    void reachableExcludesOriginWithoutCycle() {
        Graph<String> g = graph(List.of("A", "B", "C", "D"), "A>B", "B>C");
        assertEquals(Set.of("B", "C"), g.reachableFrom("A"));
        assertEquals(Set.of(), g.reachableFrom("D"));
    }

    @Test
    void reachableIncludesOriginWhenCycleReturns() {
        Graph<String> g = graph(List.of("A", "B"), "A>B", "B>A");
        assertEquals(Set.of("A", "B"), g.reachableFrom("A"));
    }

    @Test
    void shortestPathIsMinimalInEdges() {
        Graph<String> g = graph(List.of("A", "B", "C", "D"), "A>B", "B>C", "C>D", "A>D");
        assertEquals(Optional.of(List.of("A", "D")), g.shortestPath("A", "D"));
        assertEquals(Optional.of(List.of("A")), g.shortestPath("A", "A"));
        assertEquals(Optional.empty(), g.shortestPath("D", "A"));
    }

    @Test
    void noCyclesInAChain() {
        Graph<String> g = graph(List.of("A", "B", "C"), "A>B", "B>C");
        assertEquals(List.of(), g.cycles());
    }

    @Test
    void findsTriangleOnce() {
        Graph<String> g = graph(List.of("A", "B", "C"), "A>B", "B>C", "C>A");
        assertEquals(List.of(List.of("A", "B", "C")), g.cycles());
    }

    @Test
    void selfLoopIsACycleOfOne() {
        Graph<String> g = graph(List.of("A", "B"), "A>A", "A>B");
        assertEquals(List.of(List.of("A")), g.cycles());
    }

    @Test
    void findsEachElementaryCycleStartingAtItsFirstNode() {
        Graph<String> g = graph(List.of("A", "B", "C"), "A>B", "B>A", "B>C", "C>B");
        assertEquals(List.of(List.of("A", "B"), List.of("B", "C")), g.cycles());
    }

    /**
     * The three counts the {@code CycleBench} dense topology asserts. A complete digraph is a single strongly
     * connected component, so pruning the components that hold no cycle removes nothing here: the listing stays
     * whole and its cost stays proportional to the output.
     */
    @Test
    void keepsTheDenseCycleCounts() {
        assertEquals(409, completeDigraph(6).cycles().size());
        assertEquals(16064, completeDigraph(8).cycles().size());
        assertEquals(1112073, completeDigraph(10).cycles().size());
    }

    @Test
    void returnsAnImmutableListOfImmutableCycles() {
        Graph<String> g = graph(List.of("A", "B"), "A>B", "B>A");
        List<List<String>> cycles = g.cycles();
        assertEquals(List.of(List.of("A", "B")), cycles);
        assertThrows(UnsupportedOperationException.class, () -> cycles.add(List.of("A")));
        assertThrows(UnsupportedOperationException.class, () -> cycles.get(0).add("C"));
    }

    private static Graph<String> completeDigraph(int size) {
        List<String> states = new java.util.ArrayList<>();
        for (int i = 0; i < size; i++) {
            states.add("S" + i);
        }
        List<GraphEdge<String>> arcs = new java.util.ArrayList<>();
        for (int from = 0; from < size; from++) {
            for (int offset = 1; offset < size; offset++) {
                arcs.add(new GraphEdge<>(states.get(from), states.get((from + offset) % size)));
            }
        }
        return Graph.of(states, arcs);
    }

    /**
     * The cycle and component searches could never walk a null node, so {@code of} refuses one up front, like
     * {@link DirectedGraph#of} does, wherever it is declared.
     */
    @Test
    void refusesANullNode() {
        for (List<String> nodes : List.of(java.util.Arrays.asList("A", null), java.util.Arrays.asList(null, "A"))) {
            NullPointerException error = assertThrows(NullPointerException.class, () -> Graph.of(nodes, List.of()));
            assertEquals("node", error.getMessage());
        }
        assertThrows(NullPointerException.class, () -> graph(List.of("A")).successors(null));
    }

    /** The kept nodes come back in this graph's declared order, so the cycles of the result start where they did. */
    @Test
    void inducedSubgraphKeepsTheDeclaredOrderWhateverTheOrderOfKept() {
        Graph<String> g = graph(List.of("A", "B", "C"), "A>C", "C>A");
        Graph<String> induced = g.inducedSubgraph(List.of("C", "A"));
        assertEquals(List.of("A", "C"), List.copyOf(induced.nodes()));
        assertEquals(List.of(List.of("A", "C")), induced.cycles());
    }

    @Test
    void declarationIndexKeepsTheFirstAppearanceAndRejectsAStranger() {
        Graph<String> g = graph(List.of("C", "A", "C", "B"), "C>A");
        assertEquals(List.of("C", "A", "B"), List.copyOf(g.nodes()));
        assertEquals(0, g.declarationIndex("C"));
        assertEquals(1, g.declarationIndex("A"));
        assertEquals(2, g.declarationIndex("B"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> g.declarationIndex("Z"));
        assertEquals("unknown node: Z", error.getMessage());
    }
}
