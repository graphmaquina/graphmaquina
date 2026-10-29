package io.github.graphmaquina.test.fixtures;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.FailureKind;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.test.fixtures.TestDomain.Entity;
import io.github.graphmaquina.test.fixtures.TestDomain.Id;
import io.github.graphmaquina.test.fixtures.TestDomain.State;
import java.util.List;

/**
 * Fixture for the tests of the automatic vocabulary, over {@link TestDomain}'s entity, states and identifiers. NEW
 * advances by PREPARE, its only automatic edge, whose recovery rule for {@link Declined} continues the chain through
 * RECOVER to FAILED and whose rule for {@link Refused} stops it at ABANDON. READY holds two automatic edges, FINISH and
 * ABANDON, whose declared order is FINISH first although ABANDON is declared first, and FINISH has a continuing
 * {@code onFailure} route through RECOVER, so a failed READY also continues at FAILED, whose FINISH is automatic too.
 */
public final class AutomaticDomain {

    /** A failure kind whose rule continues the chain. */
    public record Declined() implements FailureKind { }

    /** A failure kind whose rule stops the chain. */
    public record Refused() implements FailureKind { }

    private AutomaticDomain() { }

    public static GraphDefinition<Entity, State, Id> graph() {
        return graph(true);
    }

    /** The same graph with PREPARE automatic or, when {@code prepareAutomatic} is false, requested by hand. */
    public static GraphDefinition<Entity, State, Id> graph(boolean prepareAutomatic) {
        TransitionBuilder<Entity, State, Id> prepare = StateGraph.define(TestDomain.KEY).initial(State.NEW)
                .terminal(State.DONE)
                .statePolicy(State.NEW, "prepare first")
                .statePolicy(State.READY, "finish only")
                .statePolicy(State.FAILED, "a failed entity only finishes")
                .transition(Id.PREPARE, State.NEW, State.READY);
        return (prepareAutomatic ? prepare.automatic() : prepare)
                    .action("prepare", (TransitionContext<Entity, State, Id> ctx) -> ActionOutcome.ok())
                    .guard("open", (GuardContext<Entity, State, Id> ctx) -> GuardResult.pass())
                    .recoverAndContinue(Declined.class, Id.RECOVER)
                    .recover(Refused.class, Id.ABANDON)
                    .endTransition()
                .transition(Id.RECOVER, State.NEW, State.FAILED).endTransition()
                .transition(Id.ABANDON, State.NEW, State.DONE).endTransition()
                .transition(Id.ABANDON, State.READY, State.DONE).automatic().endTransition()
                .transition(Id.FINISH, State.READY, State.DONE).automatic()
                    .action("finish", (TransitionContext<Entity, State, Id> ctx) -> ActionOutcome.ok())
                    .guard("ready", (GuardContext<Entity, State, Id> ctx) -> GuardResult.pass())
                    .onFailureAndContinue(Id.RECOVER)
                    .endTransition()
                .transition(Id.RECOVER, State.READY, State.FAILED).endTransition()
                .transition(Id.FINISH, State.FAILED, State.DONE).automatic().endTransition()
                .automaticOrder(State.READY, List.of(Id.FINISH, Id.ABANDON))
                .build();
    }
}
