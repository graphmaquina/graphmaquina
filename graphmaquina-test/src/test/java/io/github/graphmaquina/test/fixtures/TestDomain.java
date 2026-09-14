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
 * Shared fixture for this module's own tests: a small NEW/READY/DONE/FAILED machine exercising an
 * async action, guards, a timeout, an onFailure route and an after-commit hook.
 */
public final class TestDomain {

    /** States of {@link Entity}. */
    public enum State { NEW, READY, DONE, FAILED }

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

    public static final MachineKey<Entity, State> KEY = MachineKey.of(Entity.class, State.class);

    private TestDomain() { }

    public static GraphDefinition<Entity, State> graph(Duration timeout) {
        return graph(timeout, ctx -> CompletableFuture.completedFuture(ActionOutcome.ok()));
    }

    public static GraphDefinition<Entity, State> graph(
            Duration timeout, AsyncTransitionAction<Entity, State> action) {
        return StateGraph.define(KEY).initial(State.NEW)
                .terminal(State.DONE).terminal(State.FAILED)
                .from(State.NEW)
                    .to(State.READY).asyncAction("prepare", action)
                        .guard("first", ctx -> GuardResult.pass())
                        .guard("second", ctx -> GuardResult.pass())
                        .timeout(timeout).onFailure(State.FAILED).failsOn(IOException.class)
                        .afterCommit("audit", entity -> { })
                    .to(State.FAILED).move().stay().forbidOthers("prepare first")
                .from(State.READY).to(State.DONE).move().stay().forbidOthers("finish only")
                .from(State.DONE).stay().forbidOthers("done is final")
                .from(State.FAILED).stay().forbidOthers("failed is final")
                .build();
    }

    public static MachineRegistry registry(GraphDefinition<Entity, State> graph) {
        return MachineRegistry.builder()
                .add(StateMachines.from(graph).state(Entity::state, Entity::setState)
                        .identity(Entity::id).asyncTimeout(Duration.ofSeconds(5)))
                .build();
    }
}
