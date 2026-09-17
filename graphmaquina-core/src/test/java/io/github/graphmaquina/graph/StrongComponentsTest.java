package io.github.graphmaquina.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class StrongComponentsTest {

    /** Equality is by stable id; labels expose which equal instance the witness returns. */
    private record LabelledNode(int id, String label) {
        @Override public boolean equals(Object other) {
            return other instanceof LabelledNode node && id == node.id;
        }
        @Override public int hashCode() { return id; }
        @Override public String toString() { return label; }
    }

    @Test void nullDeclarationsPreserveComponentMembershipExceptions() {
        Graph<String> graph = Graph.of(java.util.Arrays.asList("A", null), List.of(new GraphEdge<>("A", "A")));
        assertTrue(graph.nodes().contains(null));
        assertThrows(NullPointerException.class, () -> StrongComponents.cycleWitness(graph, Set.of("A")));
        assertEquals(List.of("A", "A"),
                StrongComponents.cycleWitness(graph, new LinkedHashSet<>(List.of("A"))));
    }

    @Test void witnessesStartWithTheFirstDeclaredEqualRepresentative() {
        LabelledNode a = new LabelledNode(1, "declared A");
        LabelledNode duplicate = new LabelledNode(1, "duplicate A");
        LabelledNode b = new LabelledNode(2, "declared B");
        LabelledNode callerA = new LabelledNode(1, "caller A");
        LabelledNode callerB = new LabelledNode(2, "caller B");
        for (boolean selfLoop : List.of(true, false)) {
            Graph<LabelledNode> graph = Graph.of(List.of(a, duplicate, b), selfLoop
                    ? List.of(new GraphEdge<>(a, a))
                    : List.of(new GraphEdge<>(a, b), new GraphEdge<>(b, a)));
            Set<LabelledNode> component = selfLoop ? Set.of(callerA) : Set.of(callerB, callerA);
            List<LabelledNode> witness = StrongComponents.cycleWitness(graph, component);
            assertSame(a, witness.getFirst());
            assertEquals(selfLoop ? "[declared A, declared A]" : "[declared A, declared B, declared A]",
                    witness.toString());
            Set<LabelledNode> sorted = new TreeSet<>(Comparator.comparingInt(LabelledNode::id).reversed());
            sorted.addAll(component);
            assertEquals(witness.toString(), StrongComponents.cycleWitness(graph, sorted).toString());
        }
    }

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

    /**
     * The search starts at the first declared member but closes on the first back edge it meets, which here is a
     * sub-cycle that leaves {@code A} out. This is the documented selection, not an accident to be corrected.
     */
    @Test void keepsFirstBackEdgeWitness() {
        Graph<String> graph = Graph.of(List.of("A", "B", "C"), List.of(
                new GraphEdge<>("A", "B"), new GraphEdge<>("B", "C"),
                new GraphEdge<>("C", "B"), new GraphEdge<>("C", "A")));
        assertEquals(List.of("B", "C", "B"),
                StrongComponents.cycleWitness(graph, Set.of("A", "B", "C")));
    }

    @Test void theComponentIterationOrderDoesNotChangeTheWitness() {
        Graph<String> graph = topology(List.of("A", "B", "C"),
                List.of(edge("A", "B"), edge("B", "C"), edge("C", "A")));
        Set<String> declared = new LinkedHashSet<>(List.of("A", "B", "C"));
        Set<String> reversed = new LinkedHashSet<>(List.of("C", "B", "A"));
        Set<String> sortedBackwards = new TreeSet<>(Comparator.reverseOrder());
        sortedBackwards.addAll(List.of("A", "B", "C"));
        assertEquals(List.of("A", "B", "C", "A"), StrongComponents.cycleWitness(graph, declared));
        assertEquals(List.of("A", "B", "C", "A"), StrongComponents.cycleWitness(graph, reversed));
        assertEquals(List.of("A", "B", "C", "A"), StrongComponents.cycleWitness(graph, sortedBackwards));
    }

    @Test void anEmptyComponentIsRejectedBeforeAnythingElse() {
        Graph<String> graph = topology(List.of("A"), List.of(edge("A", "A")));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> StrongComponents.cycleWitness(graph, Set.of()));
        assertEquals("an empty component holds no cycle", error.getMessage());
    }

    @Test void aMemberOutsideTheGraphNamesTheWholeComponent() {
        Graph<String> graph = topology(List.of("A", "B"), List.of(edge("A", "B"), edge("B", "A")));
        Set<String> component = new LinkedHashSet<>(List.of("A", "Z"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> StrongComponents.cycleWitness(graph, component));
        assertEquals("the component [A, Z] holds a node outside the graph", error.getMessage());
    }

    @Test void aComponentWithoutACycleNamesTheWholeComponent() {
        Graph<String> graph = topology(List.of("A", "B"), List.of(edge("A", "B")));
        Set<String> component = new LinkedHashSet<>(List.of("A"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> StrongComponents.cycleWitness(graph, component));
        assertEquals("the component [A] holds no cycle", error.getMessage());
    }

    @Test void manySmallComponentsEachWitnessTheirOwnSelfLoop() {
        List<String> nodes = new ArrayList<>();
        List<DirectedEdge<String, String>> edges = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            nodes.add("n" + i);
        }
        for (int i = 0; i < 500; i++) {
            edges.add(new DirectedEdge<>(nodes.get(i), "loop" + i, nodes.get(i)));
            if (i + 1 < 500) {
                edges.add(edge(nodes.get(i), nodes.get(i + 1)));
            }
        }
        Graph<String> graph = topology(nodes, edges);
        List<Set<String>> components = StrongComponents.find(graph);
        assertEquals(500, components.size());
        for (int i = 0; i < 500; i++) {
            assertEquals(List.of(nodes.get(i), nodes.get(i)),
                    StrongComponents.cycleWitness(graph, components.get(i)));
        }
    }

    @Test void theWitnessIsImmutable() {
        Graph<String> graph = topology(List.of("A", "B"), List.of(edge("A", "B"), edge("B", "A")));
        List<String> witness = StrongComponents.cycleWitness(graph, Set.of("A", "B"));
        assertEquals(List.of("A", "B", "A"), witness);
        assertThrows(UnsupportedOperationException.class, () -> witness.add("C"));
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
