package io.github.graphmaquina.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;

/**
 * Counts the work {@link StrongComponents#cycleWitness} asks of the component it is given and of the nodes
 * themselves. A witness has to cost the size of its own component, so a graph made of many small components must
 * not pay one walk over every node per component. The node hashes and the node equality comparisons are counted
 * alongside the set's membership checks so that a scan merely moved from the caller's set into another
 * container still shows up here, whether that container hashes its elements or compares them one by one.
 *
 * <p>These are operation counts, not timings: they are deterministic and reproduce on any machine.
 */
class WitnessComplexityTest {

    private static final int VERTICES = 1_000;

    @Test
    void oneWitnessPerComponentDoesNotWalkEveryNode() {
        List<CountedNode> nodes = new ArrayList<>();
        for (int i = 0; i < VERTICES; i++) {
            nodes.add(new CountedNode(i));
        }
        List<GraphEdge<CountedNode>> edges = new ArrayList<>();
        for (int i = 0; i < VERTICES; i++) {
            edges.add(new GraphEdge<>(nodes.get(i), nodes.get(i)));
            if (i + 1 < VERTICES) {
                edges.add(new GraphEdge<>(nodes.get(i), nodes.get(i + 1)));
            }
        }
        Graph<CountedNode> graph = Graph.of(nodes, edges);
        List<Set<CountedNode>> components = StrongComponents.find(graph);
        assertEquals(VERTICES, components.size());

        long membershipChecks = 0L;
        CountedNode.hashes = 0L;
        CountedNode.equalities = 0L;
        for (Set<CountedNode> component : components) {
            CountingSet<CountedNode> counted = new CountingSet<>(component);
            assertEquals(2, StrongComponents.cycleWitness(graph, counted).size());
            membershipChecks += counted.containsCalls;
        }
        long hashes = CountedNode.hashes;
        long equalities = CountedNode.equalities;

        assertTrue(membershipChecks <= 8L * VERTICES,
                "the " + VERTICES + " witnesses asked the components for membership " + membershipChecks
                        + " times, above the 8 * " + VERTICES + " budget");
        assertTrue(hashes <= 32L * VERTICES,
                "the " + VERTICES + " witnesses hashed nodes " + hashes + " times, above the 32 * " + VERTICES
                        + " budget: the node walk was moved into another container rather than removed");
        assertTrue(equalities <= 32L * VERTICES,
                "the " + VERTICES + " witnesses compared nodes " + equalities + " times, above the 32 * "
                        + VERTICES + " budget: a walk through a list costs equals rather than hashes");
    }

    /**
     * A node whose hashes and equality comparisons are both counted, so that a scan relocated into a map or a
     * set is still visible and so is one relocated into a list, which costs equals and no hash at all.
     */
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    private static final class CountedNode {

        private static long hashes;

        private static long equalities;

        private final int value;

        @Override
        public int hashCode() {
            hashes++;
            return value;
        }

        @Override
        public boolean equals(Object other) {
            equalities++;
            return other instanceof CountedNode node && node.value == value;
        }

        @Override
        public String toString() {
            return "n" + value;
        }
    }

    /** Delegates to a real set and counts how often the production code asks it for membership. */
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    private static final class CountingSet<N> extends AbstractSet<N> {

        private final Set<N> delegate;

        private long containsCalls;

        @Override
        public Iterator<N> iterator() {
            return delegate.iterator();
        }

        @Override
        public int size() {
            return delegate.size();
        }

        @Override
        public boolean contains(Object candidate) {
            containsCalls++;
            return delegate.contains(candidate);
        }
    }
}
