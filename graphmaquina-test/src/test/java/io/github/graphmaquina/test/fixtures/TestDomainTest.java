package io.github.graphmaquina.test.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionRef;
import io.github.graphmaquina.api.EdgeKind;
import io.github.graphmaquina.api.EdgeSpec;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardRef;
import io.github.graphmaquina.api.HookRef;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.runtime.MachineRegistry;
import io.github.graphmaquina.test.fixtures.TestDomain.Entity;
import io.github.graphmaquina.test.fixtures.TestDomain.State;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Contract of the shared fixture. {@link TestDomain} is only type-checked by the tests that use its
 * types, so this class executes it and pins the shape every later task reads off it: the kind of each
 * cell of the matrix, the full description of the async cell, the initial state, the terminals, and
 * that a registry can actually be built from an asynchronous and from a purely synchronous graph.
 */
class TestDomainTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(1);

    /** One entry per cell, indexed by {@code from.ordinal() * 4 + to.ordinal()} over NEW, READY, DONE, FAILED. */
    private static final List<EdgeKind> EXPECTED_KINDS = List.of(
            EdgeKind.STAY, EdgeKind.ACTION, EdgeKind.FORBID, EdgeKind.MOVE,
            EdgeKind.FORBID, EdgeKind.STAY, EdgeKind.MOVE, EdgeKind.FORBID,
            EdgeKind.FORBID, EdgeKind.FORBID, EdgeKind.STAY, EdgeKind.FORBID,
            EdgeKind.FORBID, EdgeKind.FORBID, EdgeKind.FORBID, EdgeKind.STAY);

    @Test
    void everyCellOfTheMatrixKeepsItsDeclaredKind() {
        GraphDefinition<Entity, State> graph = TestDomain.graph(TIMEOUT);
        List<EdgeKind> kinds = new ArrayList<>();
        for (State from : State.values()) {
            for (State to : State.values()) {
                kinds.add(graph.edge(from, to).kind());
            }
        }
        assertEquals(EXPECTED_KINDS, kinds);
    }

    @Test
    void theActionCellCarriesItsWholeShape() {
        EdgeSpec<Entity, State> edge = TestDomain.graph(TIMEOUT).edge(State.NEW, State.READY);
        assertEquals(EdgeKind.ACTION, edge.kind());
        assertEquals(Optional.of("prepare"), edge.action().map(ActionRef::name));
        assertTrue(edge.action().orElseThrow().isAsync(), "the prepare action must be asynchronous");
        List<String> guards = edge.guards().stream().map(GuardRef::name).toList();
        assertEquals(List.of("first", "second"), guards);
        assertEquals(Optional.of(TIMEOUT), edge.timeout());
        assertEquals(Optional.of(State.FAILED), edge.onFailure());
        assertEquals(List.of(IOException.class), edge.failsOn());
        assertEquals(Optional.of("audit"), edge.afterCommit().map(HookRef::name));
    }

    @Test
    void theGraphDeclaresItsInitialStateTerminalsAndAsyncCells() {
        GraphDefinition<Entity, State> graph = TestDomain.graph(TIMEOUT);
        assertEquals(State.NEW, graph.initial());
        assertEquals(Set.of(State.DONE, State.FAILED), graph.terminals());
        assertTrue(graph.hasAsync(), "the fixture must expose an asynchronous cell");
    }

    @Test
    void theFixtureBuildsARegistryForItsAsynchronousGraph() {
        GraphDefinition<Entity, State> graph = TestDomain.graph(TIMEOUT);
        try (MachineRegistry registry = TestDomain.registry(graph)) {
            assertNotNull(registry.of(TestDomain.KEY));
        }
    }

    @Test
    void theFixtureBuildsARegistryForAGraphWithoutAsynchronousCells() {
        GraphDefinition<Entity, State> graph = graphWithoutAsyncCells();
        assertFalse(graph.hasAsync(), "this graph must have no asynchronous cell");
        try (MachineRegistry registry = TestDomain.registry(graph)) {
            assertNotNull(registry.of(TestDomain.KEY));
        }
    }

    private static GraphDefinition<Entity, State> graphWithoutAsyncCells() {
        return StateGraph.define(TestDomain.KEY).initial(State.NEW)
                .terminal(State.DONE).terminal(State.FAILED)
                .from(State.NEW)
                    .to(State.READY).move()
                    .to(State.FAILED).move().stay().forbidOthers("prepare first")
                .from(State.READY).to(State.DONE).move().stay().forbidOthers("finish only")
                .from(State.DONE).stay().forbidOthers("done is final")
                .from(State.FAILED).stay().forbidOthers("failed is final")
                .build();
    }
}
