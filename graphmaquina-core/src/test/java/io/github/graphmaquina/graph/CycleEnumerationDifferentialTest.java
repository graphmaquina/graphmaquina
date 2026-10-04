package io.github.graphmaquina.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Differential oracle for {@link Graph#cycles()}. Pruning the components that hold no cycle may only remove
 * work, never change an answer, so every graph in these corpora is enumerated twice: once by the recursive
 * search the library shipped before the pruning, kept below as {@code referenceCycles}, and once by the
 * production method. The two lists are compared whole and in order, because the published contract fixes the
 * order of the cycles as well as their contents.
 *
 * <p>The corpora are every one of the 512 directed graphs over three nodes, those same 512 under each of the
 * six declaration orders, and a pseudorandom corpus of four- and six-node graphs drawn from a fixed seed, with
 * the node order and the edge order both shuffled so that successor order is exercised too.
 */
class CycleEnumerationDifferentialTest {

    private static final int THREE_NODE_GRAPHS = 512;

    private static final int SAMPLES_PER_SIZE = 400;

    /**
     * The enumeration {@code Graph.cycles()} carried before the SCC pruning, copied from
     * {@code graphmaquina-core/src/main/java/io/github/graphmaquina/graph/Graph.java} at commit {@code 278640e}
     * (the merge of pull request #17, the base of this branch). The body is the original one; only the reads of
     * the two private fields became the public {@link Graph#nodes()} and {@link Graph#successors(Object)},
     * because the copy lives outside the class.
     */
    private static <N> List<List<N>> referenceCycles(Graph<N> graph) {
        List<N> order = new ArrayList<>(graph.nodes());
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
            referenceSearch(graph, start, start, i, index, path, onPath, found);
        }
        return List.copyOf(found);
    }

    private static <N> void referenceSearch(Graph<N> graph, N start, N current, int minIndex, Map<N, Integer> index,
                                            Deque<N> path, Set<N> onPath, List<List<N>> found) {
        for (N next : graph.successors(current)) {
            if (next.equals(start)) {
                found.add(List.copyOf(path));
                continue;
            }
            if (index.get(next) < minIndex || onPath.contains(next)) {
                continue;
            }
            path.addLast(next);
            onPath.add(next);
            referenceSearch(graph, start, next, minIndex, index, path, onPath, found);
            path.removeLast();
            onPath.remove(next);
        }
    }

    @Test
    void oneNodeAndDuplicateEdgesMatchTheOracle() {
        GraphEdge<String> loop = new GraphEdge<>("A", "A");
        for (List<GraphEdge<String>> edges : List.of(List.<GraphEdge<String>>of(), List.of(loop), List.of(loop, loop))) {
            Graph<String> graph = Graph.of(List.of("A"), edges);
            assertEquals(referenceCycles(graph), graph.cycles());
            assertEquals(edges.isEmpty() ? List.of() : List.of(List.of("A")), graph.cycles());
        }
        Graph<String> duplicates = Graph.of(List.of("A", "B"), List.of(
                new GraphEdge<>("A", "B"), new GraphEdge<>("A", "B"),
                new GraphEdge<>("B", "A"), new GraphEdge<>("B", "A")));
        assertEquals(List.of(List.of("A", "B")), duplicates.cycles());
        assertEquals(referenceCycles(duplicates), duplicates.cycles());
    }

    @Test
    void matchesTheOracleOnEveryThreeNodeGraph() {
        List<String> names = List.of("A", "B", "C");
        int compared = 0;
        for (int mask = 0; mask < THREE_NODE_GRAPHS; mask++) {
            Graph<String> graph = Graph.of(names, edgesOf(names, mask));
            assertEquals(referenceCycles(graph), graph.cycles(), "edge mask " + mask);
            compared++;
        }
        assertEquals(THREE_NODE_GRAPHS, compared);
    }

    @Test
    void matchesTheOracleUnderEveryDeclarationOrder() {
        List<List<String>> orders = List.of(
                List.of("A", "B", "C"), List.of("A", "C", "B"), List.of("B", "A", "C"),
                List.of("B", "C", "A"), List.of("C", "A", "B"), List.of("C", "B", "A"));
        int compared = 0;
        for (List<String> order : orders) {
            for (int mask = 0; mask < THREE_NODE_GRAPHS; mask++) {
                Graph<String> graph = Graph.of(order, edgesOf(order, mask));
                assertEquals(referenceCycles(graph), graph.cycles(), "order " + order + " edge mask " + mask);
                compared++;
            }
        }
        assertEquals(orders.size() * THREE_NODE_GRAPHS, compared);
    }

    @Test
    void matchesTheOracleOnAPseudorandomCorpus() {
        Random random = new Random(9L);
        int compared = 0;
        for (int size : new int[] {4, 6}) {
            for (int sample = 0; sample < SAMPLES_PER_SIZE; sample++) {
                Graph<String> graph = randomGraph(random, size);
                assertEquals(referenceCycles(graph), graph.cycles(),
                        "size " + size + " sample " + sample + " " + graph);
                compared++;
            }
        }
        assertEquals(2 * SAMPLES_PER_SIZE, compared);
    }

    /** Two cycles whose members alternate in the declared order, joined by a bridge that closes nothing. */
    @Test
    void keepsInterleavedComponentsInGlobalRootOrder() {
        Graph<String> graph = Graph.of(List.of("A", "B", "C", "D"), List.of(
                new GraphEdge<>("A", "C"), new GraphEdge<>("C", "A"),
                new GraphEdge<>("B", "D"), new GraphEdge<>("D", "B"),
                new GraphEdge<>("A", "B"), new GraphEdge<>("C", "C")));
        assertEquals(List.of(List.of("A", "C"), List.of("B", "D"), List.of("C")), graph.cycles());
    }

    @Test
    void matchesTheOracleOnInterleavedComponentsWithBridges() {
        Graph<String> graph = Graph.of(List.of("A", "B", "C", "D", "E", "F"), List.of(
                new GraphEdge<>("A", "C"), new GraphEdge<>("C", "E"), new GraphEdge<>("E", "A"),
                new GraphEdge<>("C", "A"), new GraphEdge<>("B", "D"), new GraphEdge<>("D", "F"),
                new GraphEdge<>("F", "B"), new GraphEdge<>("D", "B"),
                new GraphEdge<>("A", "B"), new GraphEdge<>("C", "F"), new GraphEdge<>("E", "D")));
        assertEquals(referenceCycles(graph), graph.cycles());
        assertEquals(4, graph.cycles().size());
    }

    /** One edge per set bit of {@code mask}, in a fixed order, so a mask names the same graph every run. */
    private static List<GraphEdge<String>> edgesOf(List<String> names, int mask) {
        List<GraphEdge<String>> edges = new ArrayList<>();
        int bit = 0;
        for (String from : names) {
            for (String to : names) {
                if ((mask & (1 << bit)) != 0) {
                    edges.add(new GraphEdge<>(from, to));
                }
                bit++;
            }
        }
        return edges;
    }

    private static Graph<String> randomGraph(Random random, int size) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            names.add("n" + i);
        }
        Collections.shuffle(names, random);
        double density = 0.15 + 0.7 * random.nextDouble();
        List<GraphEdge<String>> edges = new ArrayList<>();
        for (String from : names) {
            for (String to : names) {
                if (random.nextDouble() < density) {
                    edges.add(new GraphEdge<>(from, to));
                }
            }
        }
        Collections.shuffle(edges, random);
        return Graph.of(names, edges);
    }
}
