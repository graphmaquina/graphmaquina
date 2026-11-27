package io.github.graphmaquina.rust;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.graph.Graph;
import io.github.graphmaquina.graph.GraphEdge;
import io.github.graphmaquina.graph.StrongComponents;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Differential oracle: the Rust-backed {@link RustGraphs} must answer exactly what the Java library answers, the
 * same lists in the same order holding the same node instances, and refuse what it refuses with the same exception.
 * The oracle is the untouched production code, {@link StrongComponents#find} and {@link Graph#cycles()}.
 *
 * <p>The corpora are every directed graph over three nodes under all six declaration orders, a seeded random corpus
 * of graphs up to twelve nodes whose node and edge orders are both shuffled and whose edges repeat, a node type
 * whose equal instances are distinct objects, and graphs deep enough to overflow a recursive search.
 */
class RustGraphsDifferentialTest {

    private static final List<List<String>> ORDERS = List.of(List.of("A", "B", "C"), List.of("A", "C", "B"),
            List.of("B", "A", "C"), List.of("B", "C", "A"), List.of("C", "A", "B"), List.of("C", "B", "A"));

    @Test
    void everyThreeNodeGraphUnderEveryOrderMatchesTheLibrary() {
        List<String> names = List.of("A", "B", "C");
        for (int mask = 0; mask < 512; mask++) {
            List<GraphEdge<String>> edges = new ArrayList<>();
            for (int bit = 0; bit < 9; bit++) {
                if ((mask & (1 << bit)) != 0) {
                    edges.add(new GraphEdge<>(names.get(bit / 3), names.get(bit % 3)));
                }
            }
            for (List<String> order : ORDERS) {
                assertSameAnswers(Graph.of(order, edges));
            }
        }
    }

    @Test
    void shuffledRandomGraphsWithRepeatedEdgesMatchTheLibrary() {
        Random random = new Random(0x5EEDL);
        for (int sample = 0; sample < 3_000; sample++) {
            int size = 1 + random.nextInt(12);
            List<String> nodes = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                nodes.add("N" + i);
            }
            Collections.shuffle(nodes, random);
            int edgeCount = random.nextInt(size * 3 + 1);
            List<GraphEdge<String>> edges = new ArrayList<>();
            for (int i = 0; i < edgeCount; i++) {
                edges.add(new GraphEdge<>(nodes.get(random.nextInt(size)), nodes.get(random.nextInt(size))));
            }
            Collections.shuffle(edges, random);
            Graph<String> graph = Graph.of(nodes, edges);
            assertEquals(ordered(StrongComponents.find(graph)), ordered(RustGraphs.components(graph)),
                    graph.toString());
            if (size <= 8) {
                assertEquals(graph.cycles(), RustGraphs.cycles(graph), graph.toString());
            }
        }
    }

    @Test
    void edgesHoldingEqualButDistinctInstancesGetTheVeryInstancesTheLibraryReturns() {
        Label declaredA = new Label("A");
        Label declaredB = new Label("B");
        Graph<Label> graph = Graph.of(List.of(declaredA, declaredB, new Label("A")),
                List.of(new GraphEdge<>(declaredA, new Label("B")), new GraphEdge<>(new Label("B"), declaredA)));
        assertSameInstances(ordered(StrongComponents.find(graph)), ordered(RustGraphs.components(graph)));
        assertSameInstances(graph.cycles(), RustGraphs.cycles(graph));
        String nodeA = "A";
        String nodeB = "B";
        Graph<String> strings = Graph.of(List.of(nodeA, nodeB),
                List.of(new GraphEdge<>(nodeA, new String("B")), new GraphEdge<>(nodeB, nodeA)));
        assertSameInstances(ordered(StrongComponents.find(strings)), ordered(RustGraphs.components(strings)));
        assertSameInstances(strings.cycles(), RustGraphs.cycles(strings));
    }

    @Test
    void aNodeTypeThatBreaksTheEqualsContractGetsTheLibrarysAnswer() {
        Lawless first = new Lawless("X");
        Lawless second = new Lawless("X");
        Graph<Lawless> graph = Graph.of(List.of(first, second),
                List.of(new GraphEdge<>(first, second), new GraphEdge<>(second, first)));
        assertSameInstances(ordered(StrongComponents.find(graph)), ordered(RustGraphs.components(graph)));
        assertSameInstances(graph.cycles(), RustGraphs.cycles(graph));
    }

    @Test
    void theKernelAnswersGraphsOfJdkValueTypesWhoseEdgesReuseTheDeclaredInstances() {
        List<String> names = List.of("A", "B", "C");
        assertTrue(RustGraphs.usesKernel(Graph.of(names,
                List.of(new GraphEdge<>(names.get(0), names.get(1)), new GraphEdge<>(names.get(1), names.get(0))))));
        assertTrue(RustGraphs.usesKernel(Graph.of(List.of(Phase.ONE, Phase.TWO),
                List.of(new GraphEdge<>(Phase.ONE, Phase.TWO)))));
        assertTrue(RustGraphs.usesKernel(Graph.of(List.of(1, 2), List.of(new GraphEdge<>(1, 2)))));
        assertTrue(RustGraphs.usesKernel(Graph.of(List.<String>of(), List.of())));
        assertFalse(RustGraphs.usesKernel(Graph.of(List.of(new Label("A")), List.of())));
        assertFalse(RustGraphs.usesKernel(Graph.of(names,
                List.of(new GraphEdge<>(names.get(0), new String("B"))))));
    }

    @Test
    void aDeepRingIsOneComponentWithoutOverflowingAnyStack() {
        List<Integer> nodes = new ArrayList<>();
        List<GraphEdge<Integer>> edges = new ArrayList<>();
        int size = 50_000;
        for (int i = 0; i < size; i++) {
            nodes.add(i);
            edges.add(new GraphEdge<>(i, (i + 1) % size));
        }
        Graph<Integer> graph = Graph.of(nodes, edges);
        assertEquals(ordered(StrongComponents.find(graph)), ordered(RustGraphs.components(graph)));
        assertEquals(graph.cycles(), RustGraphs.cycles(graph));
    }

    @Test
    void theDenseCycleBenchFixtureMatchesTheLibrary() {
        int size = 8;
        List<String> nodes = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            nodes.add("S" + i);
        }
        List<GraphEdge<String>> edges = new ArrayList<>();
        for (int from = 0; from < size; from++) {
            for (int offset = 1; offset < size; offset++) {
                edges.add(new GraphEdge<>(nodes.get(from), nodes.get((from + offset) % size)));
            }
        }
        Graph<String> graph = Graph.of(nodes, edges);
        List<List<String>> cycles = RustGraphs.cycles(graph);
        assertEquals(16_064, cycles.size());
        assertEquals(graph.cycles(), cycles);
    }

    @Test
    void theAnswersAreUnmodifiableLikeTheLibrarys() {
        Graph<String> graph = Graph.of(List.of("A", "B"),
                List.of(new GraphEdge<>("A", "B"), new GraphEdge<>("B", "A")));
        List<Set<String>> components = RustGraphs.components(graph);
        assertThrows(UnsupportedOperationException.class, () -> components.add(Set.of()));
        assertThrows(UnsupportedOperationException.class, () -> components.get(0).add("C"));
        List<List<String>> cycles = RustGraphs.cycles(graph);
        assertThrows(UnsupportedOperationException.class, () -> cycles.add(List.of()));
        assertThrows(UnsupportedOperationException.class, () -> cycles.get(0).add("C"));
    }

    @Test
    void aNullGraphIsRefusedLikeTheLibraryRefusesIt() {
        NullPointerException expected = assertThrows(NullPointerException.class, () -> StrongComponents.find(null));
        NullPointerException actual = assertThrows(NullPointerException.class, () -> RustGraphs.components(null));
        assertEquals(expected.getMessage(), actual.getMessage());
    }

    private static <N> void assertSameAnswers(Graph<N> graph) {
        assertEquals(ordered(StrongComponents.find(graph)), ordered(RustGraphs.components(graph)), graph.toString());
        assertEquals(graph.cycles(), RustGraphs.cycles(graph), graph.toString());
    }

    /** The components with the order of their members made visible, which {@code Set.equals} ignores. */
    private static <N> List<List<N>> ordered(List<Set<N>> components) {
        List<List<N>> lists = new ArrayList<>();
        for (Set<N> component : components) {
            lists.add(new ArrayList<>(component));
        }
        return lists;
    }

    /** Fails unless both lists hold the same elements, in the same order, as the very same objects. */
    private static <N> void assertSameInstances(List<List<N>> expected, List<List<N>> actual) {
        assertEquals(expected, actual);
        for (int i = 0; i < expected.size(); i++) {
            for (int j = 0; j < expected.get(i).size(); j++) {
                assertSame(expected.get(i).get(j), actual.get(i).get(j), "element " + j + " of list " + i);
            }
        }
    }

    /** A node whose equal instances are distinct objects, so an answer can be checked instance by instance. */
    private record Label(String name) { }

    /** Equal by name but hashed by identity: a node type that breaks the {@code equals}/{@code hashCode} contract. */
    private static final class Lawless {
        private final String name;

        Lawless(String name) {
            this.name = name;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Lawless lawless && lawless.name.equals(name);
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }

        @Override
        public String toString() {
            return name + "@" + System.identityHashCode(this);
        }
    }

    /** An enum node type. */
    private enum Phase { ONE, TWO }
}
