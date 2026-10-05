package io.github.graphmaquina.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * {@code DirectedGraph}: parallel edges and isolated nodes are kept; unknown, duplicate and null input is refused;
 * the collections are unmodifiable; and the size follows the declared edges.
 */
class DirectedGraphTest {

    @Test void retainsParallelEdgesAndIsolatedNodes() {
        DirectedEdge<String,String> first = new DirectedEdge<>("A", "manual", "B");
        DirectedEdge<String,String> second = new DirectedEdge<>("A", "automatic", "B");
        DirectedEdge<String,String> loop = new DirectedEdge<>("B", "touch", "B");
        DirectedGraph<String,String> graph = DirectedGraph.of(List.of("A", "B", "C"), List.of(first, second, loop));
        assertEquals(List.of(first, second), graph.outgoing("A"));
        assertEquals(List.of(first, second, loop), graph.incoming("B"));
        assertEquals(List.of("A", "B", "C"), graph.nodes());
        assertEquals(Set.of("B"), graph.topology().successors("A"));
        assertEquals(3, graph.edges().size());
    }

    @Test void rejectsEdgeWithUnknownEndpoint() {
        DirectedEdge<String, String> edge = new DirectedEdge<>("A", "go", "Z");
        assertThrows(IllegalArgumentException.class, () -> DirectedGraph.of(List.of("A", "B"), List.of(edge)));
    }

    @Test void rejectsDuplicateNode() {
        List<DirectedEdge<String, String>> noEdges = List.of();
        assertThrows(IllegalArgumentException.class, () -> DirectedGraph.of(List.of("A", "A"), noEdges));
    }

    @Test void rejectsDuplicateIdenticalEdge() {
        DirectedEdge<String, String> edge = new DirectedEdge<>("A", "go", "B");
        DirectedEdge<String, String> repeated = new DirectedEdge<>("A", "go", "B");
        assertThrows(IllegalArgumentException.class,
                () -> DirectedGraph.of(List.of("A", "B"), List.of(edge, repeated)));
    }

    @Test void preservesEdgesSharingFromAndLabelWithDifferentTargets() {
        DirectedEdge<String, String> toB = new DirectedEdge<>("A", "go", "B");
        DirectedEdge<String, String> toC = new DirectedEdge<>("A", "go", "C");
        DirectedGraph<String, String> graph = DirectedGraph.of(List.of("A", "B", "C"), List.of(toB, toC));
        assertEquals(List.of(toB, toC), graph.outgoing("A"));
    }

    @Test void rejectsNullNode() {
        List<DirectedEdge<String, String>> noEdges = List.of();
        List<String> nodesWithNull = Arrays.asList("A", null);
        assertThrows(NullPointerException.class, () -> DirectedGraph.of(nodesWithNull, noEdges));
    }

    @Test void rejectsNullLabel() {
        assertThrows(NullPointerException.class, () -> new DirectedEdge<String, String>("A", null, "B"));
    }

    @Test void rejectsNullFrom() {
        assertThrows(NullPointerException.class, () -> new DirectedEdge<String, String>(null, "go", "B"));
    }

    @Test void rejectsNullTo() {
        assertThrows(NullPointerException.class, () -> new DirectedEdge<String, String>("A", "go", null));
    }

    @Test void collectionsAreUnmodifiable() {
        DirectedEdge<String, String> edge = new DirectedEdge<>("A", "go", "B");
        DirectedGraph<String, String> graph = DirectedGraph.of(List.of("A", "B"), List.of(edge));
        assertThrows(UnsupportedOperationException.class, () -> graph.nodes().add("Z"));
        assertThrows(UnsupportedOperationException.class, () -> graph.edges().add(edge));
        assertThrows(UnsupportedOperationException.class, () -> graph.outgoing("A").add(edge));
        assertThrows(UnsupportedOperationException.class, () -> graph.incoming("B").add(edge));
    }

    @Test void outgoingAndIncomingOfUnknownNodeThrow() {
        DirectedGraph<String, String> graph = DirectedGraph.of(List.of("A"), List.of());
        assertThrows(IllegalArgumentException.class, () -> graph.outgoing("Z"));
        assertThrows(IllegalArgumentException.class, () -> graph.incoming("Z"));
    }

    /**
     * A graph of V vertices and degree explicit edges per vertex holds V x degree edges and not one more: the
     * size follows the declared edges, never the pairs of nodes.
     */
    @Test void aGraphHoldsExactlyTheDeclaredEdgesAndNeverThePairsOfNodes() {
        int vertices = 2_000;
        int degree = 8;
        List<Integer> nodes = IntStream.range(0, vertices).boxed().toList();
        List<DirectedEdge<Integer, String>> edges = new ArrayList<>();
        for (int from = 0; from < vertices; from++) {
            for (int offset = 1; offset <= degree; offset++) {
                edges.add(new DirectedEdge<>(from, "step" + offset, (from + offset) % vertices));
            }
        }
        DirectedGraph<Integer, String> graph = DirectedGraph.of(nodes, edges);
        assertEquals(vertices, graph.nodes().size());
        assertEquals(vertices * degree, graph.edges().size());
        assertEquals(degree, graph.outgoing(0).size());
        assertEquals(degree, graph.incoming(0).size());
        assertEquals(vertices * degree, graph.topology().edges().size());
    }
}
