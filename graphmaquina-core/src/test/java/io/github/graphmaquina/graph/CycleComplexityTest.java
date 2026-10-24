package io.github.graphmaquina.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;

/**
 * Counts the work {@link Graph#cycles()} asks of the nodes of a graph that holds no cycle at all, and checks
 * that a long cycle is enumerated without the Java stack.
 *
 * <p>A complete forward DAG has a number of paths exponential in its vertices and not one cycle. Enumerating it
 * path by path is the behaviour issue #9 reports, so the budget here is stated in the graph's own size: fewer
 * than {@code 100 * (V + E)} node hashes. These are operation counts, not timings; they are deterministic and
 * reproduce on any machine.
 *
 * <p>The ring is enumerated on a thread with a deliberately small stack, which is what makes it a check of the
 * algorithm rather than of the default stack size. Its size is bounded because enumerating from every root
 * still costs the square of the ring's length, whatever the pruning does: larger rings are measured outside
 * the test suite.
 */
class CycleComplexityTest {

    private static final int DAG_VERTICES = 20;

    private static final int RING_VERTICES = 5_000;

    private static final long RING_STACK_BYTES = 256L * 1024L;

    @Test
    void anAcyclicGraphIsNotExploredPathByPath() {
        List<CountedNode> nodes = new ArrayList<>();
        for (int i = 0; i < DAG_VERTICES; i++) {
            nodes.add(new CountedNode(i));
        }
        List<GraphEdge<CountedNode>> edges = new ArrayList<>();
        for (int i = 0; i < DAG_VERTICES; i++) {
            for (int j = i + 1; j < DAG_VERTICES; j++) {
                edges.add(new GraphEdge<>(nodes.get(i), nodes.get(j)));
            }
        }
        Graph<CountedNode> graph = Graph.of(nodes, edges);

        CountedNode.hashes = 0L;
        List<List<CountedNode>> cycles = graph.cycles();
        long hashes = CountedNode.hashes;

        assertEquals(List.of(), cycles);
        long budget = 100L * (DAG_VERTICES + edges.size());
        assertTrue(hashes < budget,
                "enumerating the complete forward DAG over " + DAG_VERTICES + " vertices and " + edges.size()
                        + " edges hashed nodes " + hashes + " times, at or above the " + budget
                        + " budget: the acyclic paths are still being explored one by one");
    }

    @Test
    void aLongRingIsEnumeratedWithoutTheJavaStack() throws InterruptedException {
        List<Integer> nodes = new ArrayList<>();
        for (int i = 0; i < RING_VERTICES; i++) {
            nodes.add(i);
        }
        List<GraphEdge<Integer>> edges = new ArrayList<>();
        for (int i = 0; i < RING_VERTICES; i++) {
            edges.add(new GraphEdge<>(i, (i + 1) % RING_VERTICES));
        }
        Graph<Integer> graph = Graph.of(nodes, edges);

        AtomicReference<List<List<Integer>>> enumerated = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(null, () -> {
            try {
                enumerated.set(graph.cycles());
            } catch (Throwable error) {
                failure.set(error);
            }
        }, "long-ring", RING_STACK_BYTES);
        thread.start();
        thread.join();

        assertNull(failure.get(), "a ring of " + RING_VERTICES + " nodes failed on a " + RING_STACK_BYTES
                + " byte stack: the enumeration still grows the Java stack with the cycle");
        assertEquals(List.of(nodes), enumerated.get());
    }

    /** A node whose hashes are counted, so that a scan relocated into a map or a set is still visible. */
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    private static final class CountedNode {

        private static long hashes;

        private final int value;

        @Override
        public int hashCode() {
            hashes++;
            return value;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof CountedNode node && node.value == value;
        }

        @Override
        public String toString() {
            return "n" + value;
        }
    }
}
