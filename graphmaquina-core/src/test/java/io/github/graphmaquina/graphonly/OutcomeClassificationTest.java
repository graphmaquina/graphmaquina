package io.github.graphmaquina.graphonly;

import static io.github.graphmaquina.fixtures.GraphOnlyFixture.Id.APPROVE;
import static io.github.graphmaquina.fixtures.GraphOnlyFixture.Id.AUTO_APPROVE;
import static io.github.graphmaquina.fixtures.GraphOnlyFixture.Id.FAIL;
import static io.github.graphmaquina.fixtures.GraphOnlyFixture.Id.SUBMIT;
import static io.github.graphmaquina.fixtures.GraphOnlyFixture.KEY;
import static io.github.graphmaquina.fixtures.GraphOnlyFixture.State.DONE;
import static io.github.graphmaquina.fixtures.GraphOnlyFixture.State.OPEN;
import static io.github.graphmaquina.fixtures.GraphOnlyFixture.State.REVIEW;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.MachineCall;
import io.github.graphmaquina.api.MachineObserver;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.OutcomeCode;
import io.github.graphmaquina.api.OutcomeStatus;
import io.github.graphmaquina.api.PeerView;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.api.TransitionContext;
import java.time.Duration;
import io.github.graphmaquina.dsl.GraphBuilder;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.fixtures.GatewayException;
import io.github.graphmaquina.fixtures.GraphOnlyFixture;
import io.github.graphmaquina.fixtures.GraphOnlyFixture.Id;
import io.github.graphmaquina.fixtures.GraphOnlyFixture.State;
import io.github.graphmaquina.fixtures.GraphOnlyFixture.Ticket;
import io.github.graphmaquina.runtime.MachineRegistry;
import io.github.graphmaquina.runtime.StateMachines;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The outcome's classification read from the real engine: every code an operation or a reevaluation produces with
 * the explanation it carries, a failure routed from an exception that keeps its text out of the explanation, a
 * cascade's provisional outcome, and a listener that classifies what it receives without a cast.
 */
class OutcomeClassificationTest {

    /** The peer the watches read; no test changes its state. */
    final Ticket peer = new Ticket(9, OPEN);

    @Test void anAppliedEdgeExplainsNothingAndCarriesItsRecord() {
        try (MachineRegistry registry = GraphOnlyFixture.registry(GraphOnlyFixture.builder().build())) {
            Outcome<Ticket, State, Id> result = registry.of(KEY).execute(new Ticket(1, OPEN), SUBMIT);
            assertEquals(OutcomeCode.APPLIED, result.code());
            assertEquals(OutcomeStatus.APPLIED, result.status());
            assertEquals(Optional.empty(), result.explanation());
            assertEquals(1, result.records().size());
            assertEquals(REVIEW, result.recordedState());
        }
    }

    @Test void aRequestWithoutAnEdgeIsExplainedByTheStatePolicy() {
        try (MachineRegistry registry = GraphOnlyFixture.registry(GraphOnlyFixture.builder().build())) {
            Outcome<Ticket, State, Id> result = registry.of(KEY).execute(new Ticket(1, OPEN), APPROVE);
            assertEquals(OutcomeCode.NO_TRANSITION, result.code());
            assertEquals(Optional.of("submit first"), result.explanation());
            assertEquals(List.of(), result.records());
        }
    }

    /** A reason declared for one identifier wins over the state's default, and the explanation is that reason. */
    @Test void aReasonDeclaredForTheIdentifierIsTheExplanation() {
        GraphDefinition<Ticket, State, Id> graph = GraphOnlyFixture.builder()
                .reject(OPEN, APPROVE, "approval needs a review first").build();
        try (MachineRegistry registry = GraphOnlyFixture.registry(graph)) {
            Outcome<Ticket, State, Id> result = registry.of(KEY).execute(new Ticket(1, OPEN), APPROVE);
            assertEquals(OutcomeCode.NO_TRANSITION, result.code());
            assertEquals(Optional.of("approval needs a review first"), result.explanation());
        }
    }

    @Test void aGuardRejectionIsExplainedByTheGuard() {
        try (MachineRegistry registry = GraphOnlyFixture.registry(guarded().build())) {
            Outcome<Ticket, State, Id> result = registry.of(KEY).execute(new Ticket(1, REVIEW), AUTO_APPROVE);
            assertEquals(OutcomeCode.GUARD_REJECTED, result.code());
            assertEquals(OutcomeStatus.REJECTED, result.status());
            assertEquals(Optional.of("not automatic"), result.explanation());
            assertEquals(Optional.of("automatic"), result.asRejected().flatMap(Outcome.Rejected::guard));
        }
    }

    /** The recovery edge runs an action of its own; the explanation stays the reason of the action that failed. */
    @Test void aReturnedFailureIsExplainedByTheAction() {
        GraphDefinition<Ticket, State, Id> graph = failing((TransitionContext<Ticket, State, Id> ctx) ->
                ActionOutcome.failed("the board declined"));
        try (MachineRegistry registry = GraphOnlyFixture.registry(graph)) {
            Outcome<Ticket, State, Id> result = registry.of(KEY).execute(new Ticket(1, REVIEW), APPROVE);
            assertEquals(OutcomeCode.FAILURE_ROUTED, result.code());
            assertEquals(OutcomeStatus.FAILED, result.status());
            assertEquals(Optional.of("the board declined"), result.explanation());
            assertEquals(Optional.of(FAIL), result.asFailed().map(Outcome.Failed::failureTransition));
            assertEquals(DONE, result.recordedState());
        }
    }

    /** failsOn composes the reason from the exception: it stays on the variant and out of the explanation. */
    @Test void aFailureRoutedFromAnExceptionKeepsItsTextOutOfTheExplanation() {
        GraphDefinition<Ticket, State, Id> graph = failing((TransitionContext<Ticket, State, Id> ctx) -> {
            throw new GatewayException("gateway 10.0.0.12 refused the charge");
        });
        try (MachineRegistry registry = GraphOnlyFixture.registry(graph)) {
            Outcome<Ticket, State, Id> result = registry.of(KEY).execute(new Ticket(1, REVIEW), APPROVE);
            assertEquals(OutcomeCode.FAILURE_ROUTED, result.code());
            assertEquals(OutcomeStatus.FAILED, result.status());
            assertEquals(Optional.empty(), result.explanation());
            assertEquals(Optional.of("GatewayException: gateway 10.0.0.12 refused the charge"),
                    result.asFailed().map(Outcome.Failed::reason));
        }
    }

    @Test void aReevaluationWithoutAWatchIsNoWatch() {
        try (MachineRegistry registry = GraphOnlyFixture.registry(GraphOnlyFixture.builder().build())) {
            Outcome<Ticket, State, Id> result = registry.of(KEY).reevaluate(new Ticket(1, OPEN));
            assertEquals(OutcomeCode.NO_WATCH, result.code());
            assertEquals(OutcomeStatus.IGNORED, result.status());
            assertEquals(Optional.empty(), result.explanation());
        }
    }

    @Test void aReevaluationWhoseRulesAllFailIsNoRuleMatched() {
        GraphDefinition<Ticket, State, Id> graph = GraphOnlyFixture.builder()
                .watch(REVIEW, KEY, (Ticket ticket) -> List.of(peer), (Ticket moved) -> Optional.empty())
                    .when("never", (Ticket ticket, Collection<? extends Ticket> peers,
                            PeerView<Ticket, State, Id> view) -> false)
                        .execute(APPROVE)
                    .endWatch().build();
        try (MachineRegistry registry = GraphOnlyFixture.registry(graph)) {
            Outcome<Ticket, State, Id> result = registry.of(KEY).reevaluate(new Ticket(1, REVIEW));
            assertEquals(OutcomeCode.NO_RULE_MATCHED, result.code());
            assertEquals(Optional.empty(), result.explanation());
        }
    }

    /** A reevaluation whose watch selects an edge its guard refuses reports that guard's rejection. */
    @Test void aReevaluationCanEndInAGuardRejection() {
        GraphDefinition<Ticket, State, Id> graph = guarded()
                .watch(REVIEW, KEY, (Ticket ticket) -> List.of(peer), (Ticket moved) -> Optional.empty())
                    .when("always", (Ticket ticket, Collection<? extends Ticket> peers,
                            PeerView<Ticket, State, Id> view) -> true)
                        .execute(AUTO_APPROVE)
                    .endWatch().build();
        try (MachineRegistry registry = GraphOnlyFixture.registry(graph)) {
            Outcome<Ticket, State, Id> result = registry.of(KEY).reevaluate(new Ticket(1, REVIEW));
            assertEquals(OutcomeCode.GUARD_REJECTED, result.code());
            assertEquals(Optional.of("not automatic"), result.explanation());
            assertEquals(Optional.of(AUTO_APPROVE), result.requestedTransition());
        }
    }

    /** A cascade's outcome is provisional: it reports its step, and the hops belong to the root's records. */
    @Test void aCascadedStepIsAppliedWhileItsHopsBelongToTheRoot() {
        Ticket target = new Ticket(9, OPEN);
        List<Outcome<Ticket, State, Id>> cascaded = new ArrayList<>();
        GraphDefinition<Ticket, State, Id> graph = StateGraph.define(KEY).initial(OPEN).terminal(DONE)
                .statePolicy(OPEN, "submit first").statePolicy(REVIEW, "review required")
                .transition(SUBMIT, OPEN, REVIEW).endTransition()
                .transition(APPROVE, REVIEW, DONE)
                    .action("approve", (TransitionContext<Ticket, State, Id> ctx) -> {
                        cascaded.add(ctx.cascade(KEY, target, SUBMIT));
                        return ActionOutcome.ok();
                    })
                    .endTransition()
                .build();
        try (MachineRegistry registry = GraphOnlyFixture.registry(graph)) {
            Outcome<Ticket, State, Id> root = registry.of(KEY).execute(new Ticket(1, REVIEW), APPROVE);
            assertEquals(1, cascaded.size());
            Outcome<Ticket, State, Id> step = cascaded.get(0);
            assertEquals(OutcomeCode.APPLIED, step.code());
            assertEquals(OutcomeStatus.APPLIED, step.status());
            assertEquals(List.of(), step.records());
            assertEquals(List.of(), step.reactions());
            assertEquals(OutcomeCode.APPLIED, root.code());
            assertEquals(2, root.records().size());
        }
    }

    /** An observer receives Outcome<?, ?, ?> and classifies it by value, narrowing included, without a cast. */
    @Test void anObserverClassifiesWhatItReceivesWithoutACast() {
        List<String> seen = new ArrayList<>();
        MachineObserver observer = new MachineObserver() {
            @Override
            public void onCompleted(MachineCall call, Outcome<?, ?, ?> outcome, Duration elapsed) {
                seen.add(outcome.code() + ":" + outcome.status() + ":" + outcome.explanation().orElse("-") + ":"
                        + outcome.asRejected().flatMap(Outcome.Rejected::guard).orElse("-"));
            }
        };
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(guarded().build())
                        .state((Ticket ticket) -> ticket.state, (Ticket ticket, State state) -> ticket.state = state)
                        .identity((Ticket ticket) -> ticket.id))
                .observer(observer)
                .build()) {
            Ticket ticket = new Ticket(1, OPEN);
            registry.of(KEY).execute(ticket, APPROVE);
            registry.of(KEY).execute(ticket, SUBMIT);
            registry.of(KEY).execute(ticket, AUTO_APPROVE);
            registry.of(KEY).reevaluate(ticket);
        }
        assertEquals(List.of("NO_TRANSITION:REJECTED:submit first:-", "APPLIED:APPLIED:-:-",
                "GUARD_REJECTED:REJECTED:not automatic:automatic", "NO_WATCH:IGNORED:-:-"), seen);
    }

    // --- fixtures ----------------------------------------------------------------------------------

    /** The base graph plus a parallel AUTO_APPROVE edge whose only guard always refuses. */
    private static GraphBuilder<Ticket, State, Id> guarded() {
        return GraphOnlyFixture.builder()
                .transition(AUTO_APPROVE, REVIEW, DONE)
                    .guard("automatic", (GuardContext<Ticket, State, Id> ctx) -> GuardResult.reject("not automatic"))
                    .endTransition();
    }

    /** APPROVE runs {@code approve}; a failure routes to FAIL, whose own action succeeds. */
    private static GraphDefinition<Ticket, State, Id> failing(TransitionAction<Ticket, State, Id> approve) {
        return StateGraph.define(KEY).initial(OPEN).terminal(DONE)
                .statePolicy(OPEN, "submit first").statePolicy(REVIEW, "review required")
                .transition(SUBMIT, OPEN, REVIEW).endTransition()
                .transition(APPROVE, REVIEW, DONE)
                    .action("approve", approve)
                    .onFailure(FAIL).failsOn(GatewayException.class)
                    .endTransition()
                .transition(FAIL, REVIEW, DONE)
                    .action("recover", (TransitionContext<Ticket, State, Id> ctx) -> ActionOutcome.ok())
                    .endTransition()
                .build();
    }
}
