package consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.test.GraphAssert;
import io.github.graphmaquina.test.GraphAssertionError;
import io.github.graphmaquina.test.TopologyAssert;
import io.github.graphmaquina.test.fixtures.TestDomain;
import io.github.graphmaquina.test.fixtures.TestDomain.Entity;
import io.github.graphmaquina.test.fixtures.TestDomain.Id;
import io.github.graphmaquina.test.fixtures.TestDomain.State;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Contract of {@link TopologyAssert}. Every expectation gets a match and a mismatch read off the fields
 * of the {@link GraphAssertionError}, every method gets its own null-subject case, and the answers this view
 * only forwards are pinned — reachability on the acyclic fixture and on a graph that cycles, so the origin is
 * listed only when a cycle leads back to it, cycles on the graph that cycles, so the canonical rotation of a
 * cycle cannot drift, and the shortest path on the acyclic fixture.
 */
class TopologyAssertionsTest {

    /** States of a graph with both a self-loop and a three-state cycle. */
    enum Loop { A, B, C }

    /** Transition identifiers of the cyclic graph. */
    enum Turn { RETRY, FORWARD, ONWARD, BACK }

    /** An enum that belongs to no graph under test, used to reach the topology through raw types. */
    enum Foreign { OTHER }

    private static final Duration TIMEOUT = Duration.ofSeconds(1);
    private static final String TOPOLOGY =
            Entity.class.getName() + "/" + State.class.getName() + " topology";
    private static final String LOOP_TOPOLOGY =
            String.class.getName() + "/" + Loop.class.getName() + " topology";

    // --- paths -------------------------------------------------------------------------------------

    @Test
    void pathChecksEveryAdjacentPair() {
        GraphDefinition<Entity, State, Id> graph = TestDomain.graph(Duration.ofSeconds(1));
        TopologyAssert<State> topology = GraphAssert.assertThat(graph).topology();
        topology.hasPath(List.of(State.NEW, State.READY, State.DONE));
        assertThrows(GraphAssertionError.class,
                () -> topology.hasPath(List.of(State.NEW, State.DONE)));
        topology.hasNoPath(State.DONE, State.NEW);
    }

    @Test
    void aBrokenStepIsReportedByItsOwnIndex() {
        TopologyAssert<State> topology = topology();
        assertSame(topology, topology.hasPath(List.of(State.NEW, State.FAILED)));
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> topology.hasPath(List.of(State.NEW, State.DONE))),
                TOPOLOGY, "path[1]", Boolean.TRUE, Boolean.FALSE);
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> topology.hasPath(List.of(State.NEW, State.READY, State.FAILED))),
                TOPOLOGY, "path[2]", Boolean.TRUE, Boolean.FALSE);
    }

    /** Only a declared self-loop is a step from a node to itself; a state with no such edge has none. */
    @Test
    void aStateWithoutASelfLoopIsNotAStepOfAPathToItself() {
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> topology().hasPath(List.of(State.DONE, State.DONE))),
                TOPOLOGY, "path[1]", Boolean.TRUE, Boolean.FALSE);
        loopTopology().hasPath(List.of(Loop.A, Loop.A, Loop.B));
    }

    @Test
    void aSinglePathNodeOnlyHasToExistAndAnEmptyPathIsAnArgumentError() {
        TopologyAssert<State> topology = topology();
        topology.hasPath(List.of(State.DONE));
        assertEquals("path must not be empty", assertThrows(IllegalArgumentException.class,
                () -> topology.hasPath(List.of())).getMessage());
        assertThrows(NullPointerException.class,
                () -> topology.hasPath(Arrays.asList(State.NEW, null)));
    }

    @Test
    void noPathIsTheExactNegationOfTheShortestPath() {
        TopologyAssert<State> topology = topology();
        assertSame(topology, topology.hasNoPath(State.DONE, State.NEW));
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> topology.hasNoPath(State.NEW, State.DONE)),
                TOPOLOGY + " NEW -> DONE", "path", Optional.empty(),
                Optional.of(List.of(State.NEW, State.READY, State.DONE)));
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> topology.hasNoPath(State.DONE, State.DONE)),
                TOPOLOGY + " DONE -> DONE", "path", Optional.empty(), Optional.of(List.of(State.DONE)));
    }

    @Test
    void theShortestPathIsTheGraphsOwnAnswer() {
        TopologyAssert<State> topology = topology();
        assertSame(topology, topology.hasShortestPath(State.NEW, State.DONE,
                List.of(State.NEW, State.READY, State.DONE)));
        topology.hasShortestPath(State.DONE, State.DONE, List.of(State.DONE));
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> topology.hasShortestPath(State.NEW, State.DONE, List.of(State.NEW, State.DONE))),
                TOPOLOGY + " NEW -> DONE", "shortest path",
                Optional.of(List.of(State.NEW, State.DONE)),
                Optional.of(List.of(State.NEW, State.READY, State.DONE)));
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> topology.hasShortestPath(State.DONE, State.NEW, List.of(State.DONE, State.NEW))),
                TOPOLOGY + " DONE -> NEW", "shortest path",
                Optional.of(List.of(State.DONE, State.NEW)), Optional.empty());
    }

    // --- nodes and successors ----------------------------------------------------------------------

    @Test
    void theNodesAreEveryDeclaredState() {
        TopologyAssert<State> topology = topology();
        assertSame(topology, topology.hasNodesExactly(
                Set.of(State.NEW, State.READY, State.DONE, State.FAILED)));
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> topology.hasNodesExactly(Set.of(State.NEW, State.READY, State.DONE))),
                TOPOLOGY, "nodes", Set.of(State.NEW, State.READY, State.DONE),
                Set.of(State.NEW, State.READY, State.DONE, State.FAILED));
    }

    @Test
    void successorsAreTheDestinationsOfTheDeclaredEdges() {
        TopologyAssert<State> topology = topology();
        assertSame(topology, topology.hasSuccessorsExactly(State.NEW, Set.of(State.READY, State.FAILED)));
        topology.hasSuccessorsExactly(State.READY, Set.of(State.DONE));
        topology.hasSuccessorsExactly(State.DONE, Set.of());
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> topology.hasSuccessorsExactly(State.NEW,
                                Set.of(State.READY, State.FAILED, State.DONE))),
                TOPOLOGY + " NEW", "successors", Set.of(State.READY, State.FAILED, State.DONE),
                Set.of(State.READY, State.FAILED));
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> topology.hasSuccessorsExactly(State.DONE, Set.of(State.DONE))),
                TOPOLOGY + " DONE", "successors", Set.of(State.DONE), Set.of());
    }

    // --- reachability ------------------------------------------------------------------------------

    @Test
    void reachabilityExcludesTheOriginWhenNothingLeadsBackToIt() {
        TopologyAssert<State> topology = topology();
        assertSame(topology, topology.hasReachableExactly(State.NEW,
                Set.of(State.READY, State.DONE, State.FAILED)));
        topology.hasReachableExactly(State.DONE, Set.of());
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> topology.hasReachableExactly(State.NEW,
                                Set.of(State.NEW, State.READY, State.DONE, State.FAILED))),
                TOPOLOGY + " NEW", "reachable",
                Set.of(State.NEW, State.READY, State.DONE, State.FAILED),
                Set.of(State.READY, State.DONE, State.FAILED));
    }

    @Test
    void reachabilityIncludesTheOriginWhenACycleReturnsToIt() {
        TopologyAssert<Loop> topology = loopTopology();
        topology.hasReachableExactly(Loop.A, Set.of(Loop.A, Loop.B, Loop.C));
        topology.hasReachableExactly(Loop.B, Set.of(Loop.A, Loop.B, Loop.C));
    }

    // --- cycles ------------------------------------------------------------------------------------

    @Test
    void anAcyclicGraphHasNoCycles() {
        TopologyAssert<State> topology = topology();
        assertSame(topology, topology.hasNoCycles());
        assertSame(topology, topology.hasCyclesExactly(List.of()));
    }

    @Test
    void cyclesKeepTheOrderAndTheRotationOfTheGraph() {
        TopologyAssert<Loop> topology = loopTopology();
        List<List<Loop>> declared = List.of(List.of(Loop.A), List.of(Loop.A, Loop.B, Loop.C));
        topology.hasCyclesExactly(declared);
        assertFailure(assertThrows(GraphAssertionError.class, topology::hasNoCycles),
                LOOP_TOPOLOGY, "cycles", List.of(), declared);
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> topology.hasCyclesExactly(
                                List.of(List.of(Loop.A), List.of(Loop.B, Loop.C, Loop.A)))),
                LOOP_TOPOLOGY, "cycles",
                List.of(List.of(Loop.A), List.of(Loop.B, Loop.C, Loop.A)), declared);
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> topology.hasCyclesExactly(
                                List.of(List.of(Loop.A, Loop.B, Loop.C), List.of(Loop.A)))),
                LOOP_TOPOLOGY, "cycles",
                List.of(List.of(Loop.A, Loop.B, Loop.C), List.of(Loop.A)), declared);
    }

    // --- arguments ---------------------------------------------------------------------------------

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void aNodeOutsideTheGraphIsRejectedBeforeAnythingIsRead() {
        TopologyAssert raw = GraphAssert.assertThat(TestDomain.graph(TIMEOUT)).topology();
        assertEquals("unknown node: OTHER", assertThrows(IllegalArgumentException.class,
                () -> raw.hasSuccessorsExactly(Foreign.OTHER, Set.of())).getMessage());
        assertEquals("unknown node: OTHER", assertThrows(IllegalArgumentException.class,
                () -> raw.hasReachableExactly(Foreign.OTHER, Set.of())).getMessage());
        assertEquals("unknown node: OTHER", assertThrows(IllegalArgumentException.class,
                () -> raw.hasPath(List.of(State.NEW, Foreign.OTHER))).getMessage());
        assertEquals("unknown node: OTHER", assertThrows(IllegalArgumentException.class,
                () -> raw.hasNoPath(Foreign.OTHER, State.NEW)).getMessage());
        assertEquals("unknown node: OTHER", assertThrows(IllegalArgumentException.class,
                () -> raw.hasShortestPath(State.NEW, Foreign.OTHER, List.of())).getMessage());
    }

    @Test
    void aNullRequiredArgumentIsAnArgumentErrorNotAnAssertionFailure() {
        TopologyAssert<State> topology = topology();
        assertEquals("expected", assertThrows(NullPointerException.class,
                () -> topology.hasNodesExactly(null)).getMessage());
        assertEquals("from", assertThrows(NullPointerException.class,
                () -> topology.hasSuccessorsExactly(null, Set.of())).getMessage());
        assertEquals("expected", assertThrows(NullPointerException.class,
                () -> topology.hasSuccessorsExactly(State.NEW, null)).getMessage());
        assertEquals("origin", assertThrows(NullPointerException.class,
                () -> topology.hasReachableExactly(null, Set.of())).getMessage());
        assertEquals("expected", assertThrows(NullPointerException.class,
                () -> topology.hasReachableExactly(State.NEW, null)).getMessage());
        assertEquals("path", assertThrows(NullPointerException.class,
                () -> topology.hasPath(null)).getMessage());
        assertEquals("from", assertThrows(NullPointerException.class,
                () -> topology.hasNoPath(null, State.NEW)).getMessage());
        assertEquals("to", assertThrows(NullPointerException.class,
                () -> topology.hasNoPath(State.NEW, null)).getMessage());
        assertEquals("from", assertThrows(NullPointerException.class,
                () -> topology.hasShortestPath(null, State.NEW, List.of(State.NEW))).getMessage());
        assertEquals("to", assertThrows(NullPointerException.class,
                () -> topology.hasShortestPath(State.NEW, null, List.of(State.NEW))).getMessage());
        assertEquals("expected", assertThrows(NullPointerException.class,
                () -> topology.hasShortestPath(State.NEW, State.DONE, null)).getMessage());
        assertEquals("expected", assertThrows(NullPointerException.class,
                () -> topology.hasCyclesExactly(null)).getMessage());
    }

    // --- one null-subject case per expectation method -----------------------------------------------

    @Test
    void nullGraphFailsHasNodesExactlyWithContext() {
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> nullTopology().hasNodesExactly(Set.of(State.NEW))),
                "graph topology", "nodes", Set.of(State.NEW), null);
    }

    @Test
    void nullGraphFailsHasSuccessorsExactlyWithContext() {
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> nullTopology().hasSuccessorsExactly(State.NEW, Set.of(State.READY))),
                "graph topology NEW", "successors", Set.of(State.READY), null);
    }

    @Test
    void nullGraphFailsHasReachableExactlyWithContext() {
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> nullTopology().hasReachableExactly(State.NEW, Set.of(State.READY))),
                "graph topology NEW", "reachable", Set.of(State.READY), null);
    }

    @Test
    void nullGraphFailsHasPathWithContext() {
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> nullTopology().hasPath(List.of(State.NEW, State.READY))),
                "graph topology", "path", List.of(State.NEW, State.READY), null);
    }

    @Test
    void nullGraphFailsHasNoPathWithContext() {
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> nullTopology().hasNoPath(State.DONE, State.NEW)),
                "graph topology DONE -> NEW", "path", Optional.empty(), null);
    }

    @Test
    void nullGraphFailsHasShortestPathWithContext() {
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> nullTopology().hasShortestPath(State.NEW, State.READY,
                                List.of(State.NEW, State.READY))),
                "graph topology NEW -> READY", "shortest path",
                Optional.of(List.of(State.NEW, State.READY)), null);
    }

    @Test
    void nullGraphFailsHasNoCyclesWithContext() {
        assertFailure(assertThrows(GraphAssertionError.class, () -> nullTopology().hasNoCycles()),
                "graph topology", "cycles", List.of(), null);
    }

    @Test
    void nullGraphFailsHasCyclesExactlyWithContext() {
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> nullTopology().hasCyclesExactly(List.of(List.of(State.NEW)))),
                "graph topology", "cycles", List.of(List.of(State.NEW)), null);
    }

    // --- helpers -----------------------------------------------------------------------------------

    private static TopologyAssert<State> topology() {
        return GraphAssert.assertThat(TestDomain.graph(TIMEOUT)).topology();
    }

    private static TopologyAssert<State> nullTopology() {
        return GraphAssert.<Entity, State, Id>assertThat(null).topology();
    }

    private static TopologyAssert<Loop> loopTopology() {
        return GraphAssert.assertThat(loopGraph()).topology();
    }

    private static void assertFailure(GraphAssertionError error, String context, String property,
            Object expected, Object actual) {
        assertEquals(context, error.context());
        assertEquals(property, error.property());
        assertEquals(expected, error.expected());
        assertEquals(actual, error.actual());
    }

    private static GraphDefinition<String, Loop, Turn> loopGraph() {
        return StateGraph.define(MachineKey.of(String.class, Loop.class, Turn.class))
                .initial(Loop.A).allowsCycle(Loop.A)
                .statePolicy(Loop.A, "go forward").statePolicy(Loop.B, "keep going")
                .statePolicy(Loop.C, "go back")
                .transition(Turn.RETRY, Loop.A, Loop.A).action("retry", ctx -> ActionOutcome.ok())
                    .endTransition()
                .transition(Turn.FORWARD, Loop.A, Loop.B).endTransition()
                .transition(Turn.ONWARD, Loop.B, Loop.C).endTransition()
                .transition(Turn.BACK, Loop.C, Loop.A).endTransition()
                .build();
    }
}
