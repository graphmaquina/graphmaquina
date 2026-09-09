package io.github.graphmaquina.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

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
}
