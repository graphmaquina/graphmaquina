package io.github.graphmaquina.semantics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.CascadePolicy;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineCall;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.MachineObserver;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.GraphBuilder;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.runtime.MachineRegistry;
import io.github.graphmaquina.runtime.StateMachines;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Raw clients must fail at the boundary, before enum names erase the type or a transaction writes state. */
class EnumBoundariesTest {
    /** States of the entity machine; OPEN is a constant with a class body. */
    enum State { OPEN { }, DONE }
    /** Transition identifiers of the entity machine; GO is a constant with a class body. */
    enum Id { GO { }, RECOVER }
    /** An enum that is neither the states nor the identifiers of the machine, with constants named like both. */
    enum Foreign { OPEN, DONE, GO, RECOVER }
    /** The entity: a single mutable state, OPEN at the start. */
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
        assertThrows(IllegalArgumentException.class, () -> applyNamed(foreignDeclarations(), role, raw));
    }

    /** Applies the action that {@code table} lists under {@code name} to {@code target}; an unlisted name is a bug. */
    private static <T> void applyNamed(Map<String, Consumer<T>> table, String name, T target) {
        Consumer<T> action = table.get(name);
        if (action == null) {
            throw new AssertionError(name);
        }
        action.accept(target);
    }

    /** Each role of a declaration, with a declaration that uses an enum that is not the machine's. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Map<String, Consumer<GraphBuilder>> foreignDeclarations() {
        Map<String, Consumer<GraphBuilder>> declarations = new LinkedHashMap<>();
        declarations.put("initial", (GraphBuilder raw) -> raw.initial(Foreign.OPEN));
        declarations.put("terminal", (GraphBuilder raw) -> raw.terminal(Foreign.DONE));
        declarations.put("cycle", (GraphBuilder raw) -> raw.allowsCycle(Foreign.OPEN));
        declarations.put("policy", (GraphBuilder raw) -> raw.statePolicy(Foreign.OPEN, "no"));
        declarations.put("reject-state", (GraphBuilder raw) -> raw.reject(Foreign.OPEN, Id.GO, "no"));
        declarations.put("reject-id", (GraphBuilder raw) -> raw.reject(State.OPEN, Foreign.GO, "no"));
        declarations.put("from", (GraphBuilder raw) -> raw.transition(Id.GO, Foreign.OPEN, State.DONE));
        declarations.put("to", (GraphBuilder raw) -> raw.transition(Id.GO, State.OPEN, Foreign.DONE));
        declarations.put("id", (GraphBuilder raw) -> raw.transition(Foreign.GO, State.OPEN, State.DONE));
        declarations.put("failure", (GraphBuilder raw) ->
                raw.transition(Id.GO, State.OPEN, State.DONE).onFailure(Foreign.RECOVER));
        declarations.put("watch-state", (GraphBuilder raw) ->
                raw.watch(Foreign.OPEN, KEY, e -> List.of(), e -> Optional.empty()));
        declarations.put("watch-id", (GraphBuilder raw) ->
                raw.watch(State.OPEN, KEY, e -> List.of(), e -> Optional.empty())
                        .when("ready", (e, peers, view) -> true).execute(Foreign.GO));
        return declarations;
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
                .observer(new MachineObserver() {
                    @Override
                    public void onCompleted(MachineCall call, Outcome<?, ?, ?> outcome, Duration elapsed) {
                        effects[0]++;
                    }
                }).build()) {
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
        assertThrows(IllegalArgumentException.class, () -> applyNamed(foreignQueries(), query, raw));
    }

    /** Each structural query, asked with an enum that is not the machine's. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Map<String, Consumer<GraphDefinition>> foreignQueries() {
        Map<String, Consumer<GraphDefinition>> queries = new LinkedHashMap<>();
        queries.put("lookup-state", (GraphDefinition raw) -> raw.findTransition(Foreign.OPEN, Id.GO));
        queries.put("lookup-id", (GraphDefinition raw) -> raw.findTransition(State.OPEN, Foreign.GO));
        queries.put("watch", (GraphDefinition raw) -> raw.watch(Foreign.OPEN));
        queries.put("policy", (GraphDefinition raw) -> raw.policy(Foreign.OPEN));
        queries.put("outgoing", (GraphDefinition raw) -> raw.outgoingFrom(Foreign.OPEN));
        return queries;
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
                    cascadeForeign(ctx, mode, peer);
                    return ActionOutcome.ok();
                }).afterCommit("hook", e -> effects[0]++).endTransition().build();
        try (MachineRegistry registry = MachineRegistry.builder().add(StateMachines.from(graph).identity(e -> {
                    if (e == peer) { peerReads[0]++; }
                    return e;
                }).state(e -> { if (e == peer) { peerReads[0]++; } return e.state; },
                        (e, state) -> { effects[0]++; e.state = state; }))
                .observer(new MachineObserver() {
                    @Override
                    public void onCompleted(MachineCall call, Outcome<?, ?, ?> outcome, Duration elapsed) {
                        effects[0]++;
                    }
                }).build()) {
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

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void cascadeForeign(TransitionContext raw, String mode, Entity peer) {
        switch (mode) {
            case "single" -> raw.cascade(KEY, peer, Foreign.GO);
            case "fail-fast", "empty-fail-fast" ->
                    raw.cascadeAll(KEY, elementsFor(mode, peer), Foreign.GO, CascadePolicy.FAIL_FAST);
            case "collect", "empty-collect" ->
                    raw.cascadeAll(KEY, elementsFor(mode, peer), Foreign.GO, CascadePolicy.COLLECT);
            default -> throw new AssertionError(mode);
        }
    }

    private static List<Entity> elementsFor(String mode, Entity peer) {
        return mode.startsWith("empty") ? List.of() : List.of(peer);
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
