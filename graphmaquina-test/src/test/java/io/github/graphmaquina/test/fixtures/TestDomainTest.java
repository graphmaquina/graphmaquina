package io.github.graphmaquina.test.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionRef;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardRef;
import io.github.graphmaquina.api.HookRef;
import io.github.graphmaquina.api.TransitionKey;
import io.github.graphmaquina.api.TransitionKind;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.runtime.MachineRegistry;
import io.github.graphmaquina.test.fixtures.TestDomain.Entity;
import io.github.graphmaquina.test.fixtures.TestDomain.Id;
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
 * types, so this class executes it and pins the shape every later task reads off it: which edges exist
 * and which identifiers have none, the full description of the async edge, the initial state, the
 * terminals, and that a registry can actually be built from an asynchronous and from a purely
 * synchronous graph.
 */
class TestDomainTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(1);

    /** The declared edges, in the canonical order the definition keeps them. */
    private static final List<String> EXPECTED_EDGES =
            List.of("NEW/PREPARE->READY", "NEW/RECOVER->FAILED", "READY/FINISH->DONE");

    @Test
    void onlyTheDeclaredEdgesExistAndTheyKeepTheirCanonicalOrder() {
        GraphDefinition<Entity, State, Id> graph = TestDomain.graph(TIMEOUT);
        List<String> edges = new ArrayList<>();
        for (TransitionSpec<Entity, State, Id> spec : graph.transitions()) {
            edges.add(spec.key() + "->" + spec.to().name());
        }
        assertEquals(EXPECTED_EDGES, edges);
        assertEquals(Optional.empty(), graph.findTransition(State.NEW, Id.ABANDON));
        assertEquals(Optional.empty(), graph.findTransition(State.READY, Id.PREPARE));
        assertEquals(new TransitionKey<>(State.NEW, Id.PREPARE),
                graph.findTransition(State.NEW, Id.PREPARE).orElseThrow().key());
    }

    @Test
    void theActionEdgeCarriesItsWholeShape() {
        TransitionSpec<Entity, State, Id> edge =
                TestDomain.graph(TIMEOUT).findTransition(State.NEW, Id.PREPARE).orElseThrow();
        assertEquals(TransitionKind.ACTION, edge.kind());
        assertEquals(State.READY, edge.to());
        assertEquals(Optional.of("prepare"), edge.action().map(ActionRef::name));
        assertTrue(edge.action().orElseThrow().isAsync(), "the prepare action must be asynchronous");
        List<String> guards = edge.guards().stream().map(GuardRef::name).toList();
        assertEquals(List.of("first", "second"), guards);
        assertEquals(Optional.of(TIMEOUT), edge.timeout());
        assertEquals(Optional.of(Id.RECOVER), edge.onFailure());
        assertEquals(List.of(IOException.class), edge.failsOn());
        assertEquals(Optional.of("audit"), edge.afterCommit().map(HookRef::name));
    }

    @Test
    void everyStateCarriesTheRejectionPolicyItDeclaredOrTheGeneratedTerminalOne() {
        GraphDefinition<Entity, State, Id> graph = TestDomain.graph(TIMEOUT);
        assertEquals("prepare first", graph.policy(State.NEW).otherwise());
        assertEquals("finish only", graph.policy(State.READY).otherwise());
        assertEquals("terminal state DONE accepts no transitions", graph.policy(State.DONE).otherwise());
        assertEquals("terminal state FAILED accepts no transitions", graph.policy(State.FAILED).otherwise());
        assertEquals("prepare first", graph.policy(State.NEW).reasonFor(Id.ABANDON));
    }

    @Test
    void theGraphDeclaresItsInitialStateTerminalsAndAsyncEdges() {
        GraphDefinition<Entity, State, Id> graph = TestDomain.graph(TIMEOUT);
        assertEquals(State.NEW, graph.initial());
        assertEquals(Set.of(State.DONE, State.FAILED), graph.terminals());
        assertTrue(graph.hasAsync(), "the fixture must expose an asynchronous edge");
    }

    @Test
    void theFixtureBuildsARegistryForItsAsynchronousGraph() {
        GraphDefinition<Entity, State, Id> graph = TestDomain.graph(TIMEOUT);
        try (MachineRegistry registry = TestDomain.registry(graph)) {
            assertNotNull(registry.of(TestDomain.KEY));
        }
    }

    @Test
    void theFixtureBuildsARegistryForAGraphWithoutAsynchronousEdges() {
        GraphDefinition<Entity, State, Id> graph = graphWithoutAsyncEdges();
        assertFalse(graph.hasAsync(), "this graph must have no asynchronous edge");
        try (MachineRegistry registry = TestDomain.registry(graph)) {
            assertNotNull(registry.of(TestDomain.KEY));
        }
    }

    private static GraphDefinition<Entity, State, Id> graphWithoutAsyncEdges() {
        return StateGraph.define(TestDomain.KEY).initial(State.NEW)
                .terminal(State.DONE).terminal(State.FAILED)
                .statePolicy(State.NEW, "prepare first")
                .statePolicy(State.READY, "finish only")
                .transition(Id.PREPARE, State.NEW, State.READY).endTransition()
                .transition(Id.RECOVER, State.NEW, State.FAILED).endTransition()
                .transition(Id.FINISH, State.READY, State.DONE).endTransition()
                .build();
    }
}
