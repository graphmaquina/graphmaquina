package io.github.graphmaquina.graph;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Differential oracle for {@link StrongComponents#cycleWitness}. Answering membership from the graph's own node
 * set and finding the first declared member through an index, instead of scanning every graph node against the
 * caller's set, may only remove work. So every strongly connected component of every graph in these corpora is
 * witnessed twice: once by the method the library shipped before that change, kept below as
 * {@code baseCycleWitness}, and once by the production method. A component that holds a cycle must give the same
 * list, compared whole and then position by position by identity, because a witness is built from the graph's
 * own instances. A component that holds none must be refused with the same exception type and message.
 *
 * <p>Each component is presented twice: as {@link StrongComponents#find} returns it, in declared order, and as a
 * caller might build it, in the opposite iteration order and with a fresh, equal instance per member. The second
 * presentation is what makes the choice of the first member and of the instance the witness starts from visible.
 * The inputs on which the two implementations are meant to differ are not part of the corpus: sets whose
 * membership disagrees with {@code equals}, and sets that are not one strongly connected component, which the base
 * refuses when its first member reaches no cycle and the production method searches on from the next member
 * ({@code aCycleTheFirstMemberDoesNotReachIsStillWitnessed} in {@link StrongComponentsTest}). Two of the four
 * divergences listed in the {@link StrongComponents#cycleWitness} Javadoc are pinned in {@link StrongComponentsTest}:
 * the rejection of a case-insensitive {@code TreeSet}
 * ({@code aComponentWhoseMembershipDisagreesWithEqualsIsRejected}) and the acceptance of an identity-based set with
 * equal but distinct members ({@code anIdentityComponentWithEqualButDistinctMembersIsAccepted}). The other two, a
 * {@code contains} that would throw and a different exception message, are only documented there.
 *
 * <p>The corpora are those of {@link CycleEnumerationDifferentialTest}: every one of the 512 directed graphs over
 * three nodes under each of the six declaration orders, and a pseudorandom corpus of four- and six-node graphs
 * drawn from the same seed, with the node order and the edge order both shuffled. Its generators are private, so
 * they are reproduced below unchanged. {@link Graph} and {@code StrongComponents.find} are the live code on both
 * sides of the comparison, so this oracle compares the two witness algorithms and not the graph they run on; the
 * cycle enumeration has its own frozen copy in {@link CycleEnumerationDifferentialTest}.
 */
class CycleWitnessDifferentialTest {

    private static final int THREE_NODE_GRAPHS = 512;

    private static final int SAMPLES_PER_SIZE = 400;

    /**
     * The witness {@code StrongComponents.cycleWitness} carried before the declaration index, copied from
     * {@code graphmaquina-core/src/main/java/io/github/graphmaquina/graph/StrongComponents.java} at commit
     * {@code 278640e} (the merge of pull request #17, the base the issue #9 work branched from), together with the
     * two private helpers it calls, {@code close} and {@code inside}. The bodies are the original ones. Three edits
     * and no others: the method is private and renamed, the Lombok {@code @NonNull} on its two parameters is
     * dropped because Lombok is not on the test compile classpath and the corpus never passes null, and this
     * comment replaces the original's own. Every call the original makes still exists on {@link Graph}, so nothing
     * else had to be adapted.
     */
    private static <N> List<N> baseCycleWitness(Graph<N> graph, Set<N> component) {
        if (component.isEmpty()) {
            throw new IllegalArgumentException("an empty component holds no cycle");
        }
        Set<N> members = new LinkedHashSet<>();
        for (N node : graph.nodes()) {
            if (component.contains(node)) {
                members.add(node);
            }
        }
        if (members.size() != component.size()) {
            throw new IllegalArgumentException("the component " + component + " holds a node outside the graph");
        }
        N start = members.iterator().next();
        Map<N, N> parent = new HashMap<>();
        Set<N> onPath = new LinkedHashSet<>();
        Set<N> done = new LinkedHashSet<>();
        Deque<N> stack = new ArrayDeque<>();
        Deque<List<N>> successors = new ArrayDeque<>();
        Deque<int[]> cursors = new ArrayDeque<>();
        stack.addLast(start);
        successors.addLast(inside(graph, start, members));
        cursors.addLast(new int[] {0});
        onPath.add(start);
        while (!stack.isEmpty()) {
            N current = stack.peekLast();
            List<N> next = successors.peekLast();
            int[] cursor = cursors.peekLast();
            if (cursor[0] == next.size()) {
                stack.removeLast();
                successors.removeLast();
                cursors.removeLast();
                onPath.remove(current);
                done.add(current);
                continue;
            }
            N candidate = next.get(cursor[0]);
            cursor[0]++;
            if (onPath.contains(candidate)) {
                return close(parent, current, candidate);
            }
            if (done.contains(candidate)) {
                continue;
            }
            parent.put(candidate, current);
            stack.addLast(candidate);
            successors.addLast(inside(graph, candidate, members));
            cursors.addLast(new int[] {0});
            onPath.add(candidate);
        }
        throw new IllegalArgumentException("the component " + component + " holds no cycle");
    }

    /** Walks the parents from {@code last} back to {@code first} and closes the list on {@code first}. */
    private static <N> List<N> close(Map<N, N> parent, N last, N first) {
        List<N> reversed = new ArrayList<>();
        N cursor = last;
        reversed.add(cursor);
        while (!cursor.equals(first)) {
            cursor = parent.get(cursor);
            reversed.add(cursor);
        }
        List<N> witness = new ArrayList<>();
        for (int i = reversed.size() - 1; i >= 0; i--) {
            witness.add(reversed.get(i));
        }
        witness.add(first);
        return List.copyOf(witness);
    }

    private static <N> List<N> inside(Graph<N> graph, N node, Set<N> members) {
        List<N> kept = new ArrayList<>();
        for (N successor : graph.successors(node)) {
            if (members.contains(successor)) {
                kept.add(successor);
            }
        }
        return kept;
    }

    @Test
    void matchesTheBaseOnEveryComponentUnderEveryDeclarationOrder() {
        List<List<String>> orders = List.of(
                List.of("A", "B", "C"), List.of("A", "C", "B"), List.of("B", "A", "C"),
                List.of("B", "C", "A"), List.of("C", "A", "B"), List.of("C", "B", "A"));
        Tally tally = new Tally();
        for (List<String> order : orders) {
            for (int mask = 0; mask < THREE_NODE_GRAPHS; mask++) {
                compareEveryComponent(Graph.of(order, edgesOf(order, mask)),
                        "order " + order + " edge mask " + mask, tally);
            }
        }
        assertEquals(orders.size() * THREE_NODE_GRAPHS, tally.graphs);
        assertBothOutcomesWereCompared(tally);
    }

    @Test
    void matchesTheBaseOnEveryComponentOfAPseudorandomCorpus() {
        Random random = new Random(9L);
        Tally tally = new Tally();
        for (int size : new int[] {4, 6}) {
            for (int sample = 0; sample < SAMPLES_PER_SIZE; sample++) {
                Graph<String> graph = randomGraph(random, size);
                compareEveryComponent(graph, "size " + size + " sample " + sample + " " + graph, tally);
            }
        }
        assertEquals(2 * SAMPLES_PER_SIZE, tally.graphs);
        assertBothOutcomesWereCompared(tally);
    }

    /** The comparison is worthless if the corpus never reaches a witness, or never reaches a refusal. */
    private static void assertBothOutcomesWereCompared(Tally tally) {
        assertTrue(tally.witnesses > 0, "no component of the corpus held a cycle");
        assertTrue(tally.refusals > 0, "no component of the corpus was refused");
    }

    private static void compareEveryComponent(Graph<String> graph, String context, Tally tally) {
        tally.graphs++;
        for (Set<String> found : StrongComponents.find(graph)) {
            compare(graph, found, context + ", component " + found + " as found", tally);
            compare(graph, handedOver(found), context + ", component " + found + " handed over", tally);
        }
    }

    /**
     * Witnesses {@code component} with the base copy and with the production method. A component the base
     * witnesses must be witnessed identically, instance by instance; one the base refuses must be refused by the
     * production method with the same exception type and message.
     */
    private static void compare(Graph<String> graph, Set<String> component, String context, Tally tally) {
        List<String> base;
        try {
            base = baseCycleWitness(graph, component);
        } catch (IllegalArgumentException baseRefusal) {
            IllegalArgumentException headRefusal = assertThrows(IllegalArgumentException.class,
                    () -> StrongComponents.cycleWitness(graph, component), context);
            assertEquals(baseRefusal.getClass(), headRefusal.getClass(), context);
            assertEquals(baseRefusal.getMessage(), headRefusal.getMessage(), context);
            tally.refusals++;
            return;
        }
        List<String> head = assertDoesNotThrow(() -> StrongComponents.cycleWitness(graph, component), context);
        assertEquals(base, head, context);
        for (int position = 0; position < base.size(); position++) {
            assertSame(base.get(position), head.get(position), context + ", position " + position);
        }
        tally.witnesses++;
    }

    /**
     * The component as a caller might build it: the opposite iteration order and a fresh, equal instance per
     * member. The components {@code find} returns hold the graph's own instances in declared order, so only this
     * copy can tell a witness that starts from the graph's instance from one that starts from the caller's.
     */
    private static Set<String> handedOver(Set<String> component) {
        List<String> reversed = new ArrayList<>(component);
        Collections.reverse(reversed);
        Set<String> copy = new LinkedHashSet<>();
        for (String member : reversed) {
            copy.add(new String(member));
        }
        return copy;
    }

    /** How many graphs were compared, and how many of the comparisons ended in a witness or in a refusal. */
    private static final class Tally {

        private int graphs;

        private int witnesses;

        private int refusals;
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
