package io.github.graphmaquina.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The numbered searches against the object searches they replace: over every lawful corpus of
 * {@link GraphCorpus} the graph is numbered, and {@link StrongComponents#find} and {@link Graph#cycles()} return,
 * element for element, the same instances in the same container classes as {@link StrongComponents#findOverObjects}
 * and {@link Graph#cyclesOverObjects()}; a graph with a user-defined node or edge endpoint is never numbered.
 */
class NumberedGraphTest {

    @Test
    void everyThreeNodeStringGraphIsNumberedAndAnsweredTheSame() {
        GraphCorpus.threeNodeStrings().forEach(NumberedGraphTest::assertNumberedTheSame);
    }

    @Test
    void randomLawfulGraphsAreNumberedAndAnsweredTheSame() {
        GraphCorpus.random(0x5EED_0011L, 3_000, 12, GraphCorpus.STRINGS)
                .forEach(NumberedGraphTest::assertNumberedTheSame);
        GraphCorpus.random(0x5EED_0012L, 1_000, 9, GraphCorpus.LONGS)
                .forEach(NumberedGraphTest::assertNumberedTheSame);
        GraphCorpus.random(0x5EED_0013L, 1_000, 7, GraphCorpus.COLOURS)
                .forEach(NumberedGraphTest::assertNumberedTheSame);
        GraphCorpus.shapes(GraphCorpus.STRINGS, 60).forEach(NumberedGraphTest::assertNumberedTheSame);
    }

    @Test
    void mixedJdkNodesAndAnEmptyGraphAreNumberedAndAnsweredTheSame() {
        List<Object> nodes = List.of("s", 1, 2L, (short) 3, (byte) 4, 'c', true, GraphCorpus.Colour.RED);
        List<GraphEdge<Object>> edges = List.of(edge("s", 1), edge(1, 2L), edge(2L, "s"), edge((short) 3, (byte) 4),
                edge((byte) 4, (short) 3), edge('c', 'c'), edge(true, GraphCorpus.Colour.RED));
        assertNumberedTheSame(Graph.of(nodes, edges));
        assertNumberedTheSame(Graph.<Object>of(List.of(), List.of()));
    }

    @Test
    void aGraphWithAUserNodeOrAUserEndpointIsNeverNumbered() {
        for (GraphCase<LoggingNode> graphCase : GraphCorpus.random(0x5EED_0014L, 200, 8, GraphCorpus.LOGGING)) {
            assertTrue(NumberedGraph.of(graphCase.graph()).isEmpty());
        }
        int impostorGraphs = 0;
        for (GraphCase<Object> graphCase : GraphCorpus.impostors(0x5EED_0015L, 400)) {
            boolean carriesAnImpostor = graphCase.graph().edges().stream()
                    .anyMatch(edge -> edge.from() instanceof ImpostorNode || edge.to() instanceof ImpostorNode);
            impostorGraphs += carriesAnImpostor ? 1 : 0;
            assertEquals(carriesAnImpostor, NumberedGraph.of(graphCase.graph()).isEmpty());
        }
        assertTrue(impostorGraphs > 100);
    }

    private static GraphEdge<Object> edge(Object from, Object to) {
        return new GraphEdge<>(from, to);
    }

    private static <N> void assertNumberedTheSame(GraphCase<N> graphCase) {
        assertNumberedTheSame(graphCase.graph());
    }

    private static <N> void assertNumberedTheSame(Graph<N> graph) {
        assertTrue(NumberedGraph.of(graph).isPresent());
        assertSameAnswer(StrongComponents.findOverObjects(graph), StrongComponents.find(graph));
        assertSameAnswer(graph.cyclesOverObjects(), graph.cycles());
    }

    /** Same list class and size, and each container of the same class holding the same instances in order. */
    private static void assertSameAnswer(List<? extends Collection<?>> expected, List<? extends Collection<?>> actual) {
        assertEquals(expected.getClass(), actual.getClass());
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i).getClass(), actual.get(i).getClass());
            assertEquals(expected.get(i).size(), actual.get(i).size());
            Iterator<?> actualElements = actual.get(i).iterator();
            expected.get(i).forEach(element -> assertSame(element, actualElements.next()));
        }
    }
}
