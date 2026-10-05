package io.github.graphmaquina.test.fixtures;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.AsyncTransitionAction;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.runtime.MachineRegistry;
import io.github.graphmaquina.runtime.StateMachines;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Shared fixture for this module's own tests: a small NEW/READY/DONE/FAILED machine whose three declared
 * edges exercise an async action, guards, a timeout, a recovery identifier and an after-commit hook. The
 * catalogue declares one identifier with no edge at all, so absence stays observable. The edges are declared
 * in an order that is neither the one a definition lists them in nor its reverse, so a test can tell sorting
 * them from keeping or reversing the order the builder was given.
 */
public final class TestDomain {

    /** States of {@link Entity}. */
    public enum State { NEW, READY, DONE, FAILED }

    /** Catalogue of transition identifiers; {@code ABANDON} deliberately has no edge. */
    public enum Id { PREPARE, RECOVER, FINISH, ABANDON }

    /** Mutable entity carrying its current {@link State}. */
    public static final class Entity {

        private final String id;
        private State state;

        public Entity(String id, State state) {
            this.id = id;
            this.state = state;
        }

        public String id() { return id; }

        public State state() { return state; }

        public void setState(State state) { this.state = state; }
    }

    public static final MachineKey<Entity, State, Id> KEY =
            MachineKey.of(Entity.class, State.class, Id.class);

    private TestDomain() { }

    public static GraphDefinition<Entity, State, Id> graph(Duration timeout) {
        return graph(timeout, ctx -> CompletableFuture.completedFuture(ActionOutcome.ok()));
    }

    public static GraphDefinition<Entity, State, Id> graph(
            Duration timeout, AsyncTransitionAction<Entity, State, Id> action) {
        return StateGraph.define(KEY).initial(State.NEW)
                .terminal(State.DONE).terminal(State.FAILED)
                .statePolicy(State.NEW, "prepare first")
                .statePolicy(State.READY, "finish only")
                .transition(Id.RECOVER, State.NEW, State.FAILED).endTransition()
                .transition(Id.FINISH, State.READY, State.DONE).endTransition()
                .transition(Id.PREPARE, State.NEW, State.READY).asyncAction("prepare", action)
                    .guard("first", ctx -> GuardResult.pass())
                    .guard("second", ctx -> GuardResult.pass())
                    .timeout(timeout).onFailure(Id.RECOVER).failsOn(IOException.class)
                    .afterCommit("audit", entity -> { })
                    .endTransition()
                .build();
    }

    public static MachineRegistry registry(GraphDefinition<Entity, State, Id> graph) {
        return MachineRegistry.builder()
                .add(StateMachines.from(graph).state(Entity::state, Entity::setState)
                        .identity(Entity::id).asyncTimeout(Duration.ofSeconds(5)))
                .build();
    }
}
