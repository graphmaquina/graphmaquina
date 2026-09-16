package io.github.graphmaquina.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StrongComponentsTest {

    private static Graph<String> topology(List<String> nodes, List<DirectedEdge<String, String>> edges) {
        return DirectedGraph.of(nodes, edges).topology();
    }

    private static DirectedEdge<String, String> edge(String from, String to) {
        return new DirectedEdge<>(from, from + ">" + to, to);
    }

    @Test void componentsFollowDeclarationOrder() {
        Graph<String> graph = topology(List.of("A", "B", "C", "D"),
                List.of(edge("A", "B"), edge("B", "A"), edge("B", "C"), edge("C", "D")));
        assertEquals(List.of(Set.of("A", "B"), Set.of("C"), Set.of("D")), StrongComponents.find(graph));
    }

    @Test void parallelEdgesDoNotChangeTheComponents() {
        DirectedEdge<String, String> first = new DirectedEdge<>("A", "one", "B");
        DirectedEdge<String, String> second = new DirectedEdge<>("A", "two", "B");
        Graph<String> graph = topology(List.of("A", "B"), List.of(first, second, edge("B", "A")));
        assertEquals(List.of(Set.of("A", "B")), StrongComponents.find(graph));
    }

    @Test void witnessCloseTheCycleOnItsFirstNode() {
        Graph<String> graph = topology(List.of("A", "B", "C"),
                List.of(edge("A", "B"), edge("B", "C"), edge("C", "A")));
        assertEquals(List.of("A", "B", "C", "A"), StrongComponents.cycleWitness(graph, Set.of("A", "B", "C")));
    }

    @Test void selfLoopIsItsOwnWitness() {
        Graph<String> graph = topology(List.of("A"), List.of(edge("A", "A")));
        assertEquals(List.of("A", "A"), StrongComponents.cycleWitness(graph, Set.of("A")));
    }

    @Test void anAcyclicComponentHasNoWitness() {
        Graph<String> graph = topology(List.of("A", "B"), List.of(edge("A", "B")));
        assertThrows(IllegalArgumentException.class, () -> StrongComponents.cycleWitness(graph, Set.of("A")));
    }

    @Test void twentyThousandNodesNeitherRecurseNorGoQuadratic() {
        List<String> nodes = new ArrayList<>();
        List<DirectedEdge<String, String>> edges = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            nodes.add("n" + i);
        }
        for (int i = 0; i < 19_999; i++) {
            edges.add(edge(nodes.get(i), nodes.get(i + 1)));
        }
        Graph<String> chain = topology(nodes, edges);
        assertEquals(20_000, StrongComponents.find(chain).size());

        edges.add(edge(nodes.get(19_999), nodes.get(0)));
        Graph<String> ring = topology(nodes, edges);
        List<Set<String>> components = StrongComponents.find(ring);
        assertEquals(1, components.size());
        assertEquals(20_000, components.get(0).size());
        List<String> witness = StrongComponents.cycleWitness(ring, components.get(0));
        assertEquals(20_001, witness.size());
        assertEquals(witness.get(0), witness.get(witness.size() - 1));
    }

    @Test void inducedSubgraphKeepsOnlyTheEdgesWithBothEndpoints() {
        Graph<String> graph = topology(List.of("A", "B", "C"),
                List.of(edge("A", "B"), edge("B", "C"), edge("C", "A")));
        Graph<String> restricted = graph.inducedSubgraph(List.of("B", "C"));
        assertEquals(Set.of("B", "C"), restricted.nodes());
        assertEquals(Set.of("C"), restricted.successors("B"));
        assertTrue(restricted.successors("C").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> graph.inducedSubgraph(List.of("Z")));
    }
}
