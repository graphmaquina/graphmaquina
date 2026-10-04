package io.github.graphmaquina.graphonly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.CascadePolicy;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.GraphBuilder;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.runtime.MachineRegistry;
import io.github.graphmaquina.runtime.StateMachines;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Raw clients must fail at the boundary, before enum names erase the type or a transaction writes state. */
class EnumBoundariesTest {
    enum State { OPEN { }, DONE }
    enum Id { GO { }, RECOVER }
    enum Foreign { OPEN, DONE, GO, RECOVER }
    static final class Entity { State state = State.OPEN; }
    private static final MachineKey<Entity, State, Id> KEY = MachineKey.of(Entity.class, State.class, Id.class);

    @Test void theFactoryRejectsTheStateEnumAsTheTransitionCatalogue() {
        assertThrows(IllegalArgumentException.class, () -> MachineKey.of(Entity.class, State.class, State.class));
    }

    @Test void thePublicConstructorAlsoRejectsOneEnumForBothRoles() {
        assertThrows(IllegalArgumentException.class, () -> new MachineKey<>(Entity.class, State.class, State.class));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void rawKeysRequireConcreteEnumClasses() {
        assertThrows(IllegalArgumentException.class, () -> new MachineKey(Entity.class, String.class, Id.class));
        assertThrows(IllegalArgumentException.class, () -> new MachineKey(Entity.class, State.class, String.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"initial", "terminal", "cycle", "policy", "reject-state", "reject-id",
            "from", "to", "id", "failure", "watch-state", "watch-id"})
    @SuppressWarnings({"rawtypes", "unchecked"})
    void refusesForeignEnumsBeforeAcceptingADeclaration(String role) {
        GraphBuilder raw = StateGraph.define(KEY);
        assertThrows(IllegalArgumentException.class, () -> {
            switch (role) {
                case "initial" -> raw.initial(Foreign.OPEN);
                case "terminal" -> raw.terminal(Foreign.DONE);
                case "cycle" -> raw.allowsCycle(Foreign.OPEN);
                case "policy" -> raw.statePolicy(Foreign.OPEN, "no");
                case "reject-state" -> raw.reject(Foreign.OPEN, Id.GO, "no");
                case "reject-id" -> raw.reject(State.OPEN, Foreign.GO, "no");
                case "from" -> raw.transition(Id.GO, Foreign.OPEN, State.DONE);
                case "to" -> raw.transition(Id.GO, State.OPEN, Foreign.DONE);
                case "id" -> raw.transition(Foreign.GO, State.OPEN, State.DONE);
                case "failure" -> raw.transition(Id.GO, State.OPEN, State.DONE).onFailure(Foreign.RECOVER);
                case "watch-state" -> raw.watch(Foreign.OPEN, KEY, e -> List.of(), e -> Optional.empty());
                case "watch-id" -> raw.watch(State.OPEN, KEY, e -> List.of(), e -> Optional.empty())
                        .when("ready", (e, peers, view) -> true).execute(Foreign.GO);
                default -> throw new AssertionError(role);
            }
        });
    }

    @Test void enumConstantsWithClassBodiesRemainExecutable() {
        GraphDefinition<Entity, State, Id> graph = graph(new int[1]);
        try (MachineRegistry registry = MachineRegistry.builder().add(StateMachines.from(graph).identity(e -> e)
                .state(e -> e.state, (e, state) -> e.state = state)).build()) {
            Entity entity = new Entity();
            assertInstanceOf(Outcome.Applied.class, registry.of(KEY).execute(entity, Id.GO));
            assertEquals(State.DONE, entity.state);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @SuppressWarnings({"rawtypes", "unchecked"})
    void foreignRequestsFailBeforeReadingStateOrRunningEffects(boolean async) {
        int[] effects = new int[1];
        GraphDefinition<Entity, State, Id> graph = graph(effects);
        try (MachineRegistry registry = MachineRegistry.builder().add(StateMachines.from(graph).identity(e -> e)
                .state(e -> { effects[0]++; return e.state; }, (e, state) -> { effects[0]++; e.state = state; }))
                .listener((key, outcome) -> effects[0]++).build()) {
            StateMachine raw = registry.of(KEY);
            Entity entity = new Entity();
            if (async) {
                CompletableFuture<?> future = raw.async().execute(entity, Foreign.GO, "payload");
                CompletionException error = assertThrows(CompletionException.class, future::join);
                assertInstanceOf(IllegalArgumentException.class, error.getCause());
            } else {
                assertThrows(IllegalArgumentException.class, () -> raw.execute(entity, Foreign.GO));
            }
            assertEquals(State.OPEN, entity.state);
            assertEquals(0, effects[0]);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"lookup-state", "lookup-id", "watch", "policy", "outgoing"})
    @SuppressWarnings({"rawtypes", "unchecked"})
    void structuralQueriesDoNotTreatForeignEnumsAsAbsentEdges(String query) {
        GraphDefinition raw = graph(new int[1]);
        assertThrows(IllegalArgumentException.class, () -> {
            switch (query) {
                case "lookup-state" -> raw.findTransition(Foreign.OPEN, Id.GO);
                case "lookup-id" -> raw.findTransition(State.OPEN, Foreign.GO);
                case "watch" -> raw.watch(Foreign.OPEN);
                case "policy" -> raw.policy(Foreign.OPEN);
                case "outgoing" -> raw.outgoingFrom(Foreign.OPEN);
                default -> throw new AssertionError(query);
            }
        });
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void findTransitionReportsTheExactTextForAWrongEnum() {
        GraphDefinition raw = graph(new int[1]);
        IllegalArgumentException wrongState = assertThrows(IllegalArgumentException.class,
                () -> raw.findTransition(Foreign.OPEN, Id.GO));
        assertEquals("state must belong to " + State.class.getName() + ": " + Foreign.OPEN, wrongState.getMessage());
        IllegalArgumentException wrongTransition = assertThrows(IllegalArgumentException.class,
                () -> raw.findTransition(State.OPEN, Foreign.GO));
        assertEquals("transition must belong to " + Id.class.getName() + ": " + Foreign.GO,
                wrongTransition.getMessage());
    }

    @Test void findTransitionRejectsNullArguments() {
        GraphDefinition<Entity, State, Id> graph = graph(new int[1]);
        NullPointerException noFrom = assertThrows(NullPointerException.class, () -> graph.findTransition(null, Id.GO));
        assertTrue(noFrom.getMessage().startsWith("from"), noFrom.getMessage());
        NullPointerException noTransition = assertThrows(NullPointerException.class,
                () -> graph.findTransition(State.OPEN, null));
        assertTrue(noTransition.getMessage().startsWith("transition"), noTransition.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"single", "fail-fast", "collect", "empty-fail-fast", "empty-collect"})
    @SuppressWarnings({"rawtypes", "unchecked"})
    void foreignCascadesFailBeforeReadingThePeerOrCommitting(String mode) {
        Entity peer = new Entity();
        int[] peerReads = new int[1];
        int[] effects = new int[1];
        GraphDefinition<Entity, State, Id> graph = StateGraph.define(KEY).initial(State.OPEN).terminal(State.DONE)
                .statePolicy(State.OPEN, "no").transition(Id.GO, State.OPEN, State.DONE).action("go", ctx -> {
                    TransitionContext raw = ctx;
                    switch (mode) {
                        case "single" -> raw.cascade(KEY, peer, Foreign.GO);
                        case "fail-fast", "empty-fail-fast" -> raw.cascadeAll(KEY,
                                mode.startsWith("empty") ? List.of() : List.of(peer), Foreign.GO, CascadePolicy.FAIL_FAST);
                        case "collect", "empty-collect" -> raw.cascadeAll(KEY,
                                mode.startsWith("empty") ? List.of() : List.of(peer), Foreign.GO, CascadePolicy.COLLECT);
                        default -> throw new AssertionError(mode);
                    }
                    return ActionOutcome.ok();
                }).afterCommit("hook", e -> effects[0]++).endTransition().build();
        try (MachineRegistry registry = MachineRegistry.builder().add(StateMachines.from(graph).identity(e -> {
                    if (e == peer) { peerReads[0]++; }
                    return e;
                }).state(e -> { if (e == peer) { peerReads[0]++; } return e.state; },
                        (e, state) -> { effects[0]++; e.state = state; }))
                .listener((key, outcome) -> effects[0]++).build()) {
            Entity entity = new Entity();
            TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                    () -> registry.of(KEY).execute(entity, Id.GO));
            assertInstanceOf(IllegalArgumentException.class, error.getCause());
            assertEquals(State.OPEN, entity.state);
            assertEquals(State.OPEN, peer.state);
            assertEquals(0, peerReads[0]);
            assertEquals(0, effects[0]);
        }
    }

    @ParameterizedTest
    @EnumSource(CascadePolicy.class)
    void aValidEmptyCascadeStillAllowsTheRootToComplete(CascadePolicy policy) {
        GraphDefinition<Entity, State, Id> graph = StateGraph.define(KEY).initial(State.OPEN).terminal(State.DONE)
                .statePolicy(State.OPEN, "no").transition(Id.GO, State.OPEN, State.DONE).action("go", ctx -> {
                    assertEquals(List.of(), ctx.cascadeAll(KEY, List.of(), Id.GO, policy));
                    return ActionOutcome.ok();
                }).endTransition().build();
        try (MachineRegistry registry = MachineRegistry.builder().add(StateMachines.from(graph).identity(e -> e)
                .state(e -> e.state, (e, state) -> e.state = state)).build()) {
            Entity entity = new Entity();
            assertInstanceOf(Outcome.Applied.class, registry.of(KEY).execute(entity, Id.GO));
            assertEquals(State.DONE, entity.state);
        }
    }

    private static GraphDefinition<Entity, State, Id> graph(int[] effects) {
        return StateGraph.define(KEY).initial(State.OPEN).terminal(State.DONE).statePolicy(State.OPEN, "no")
                .transition(Id.GO, State.OPEN, State.DONE)
                .action("go", ctx -> { effects[0]++; return ActionOutcome.ok(); })
                .afterCommit("hook", e -> effects[0]++).endTransition().build();
    }
}
