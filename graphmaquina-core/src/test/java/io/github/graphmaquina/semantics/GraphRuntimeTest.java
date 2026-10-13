package io.github.graphmaquina.semantics;

import static io.github.graphmaquina.fixtures.TicketMachineFixture.Id.APPROVE;
import static io.github.graphmaquina.fixtures.TicketMachineFixture.Id.AUTO_APPROVE;
import static io.github.graphmaquina.fixtures.TicketMachineFixture.Id.FAIL;
import static io.github.graphmaquina.fixtures.TicketMachineFixture.Id.RETOUCH;
import static io.github.graphmaquina.fixtures.TicketMachineFixture.Id.SUBMIT;
import static io.github.graphmaquina.fixtures.TicketMachineFixture.KEY;
import static io.github.graphmaquina.fixtures.TicketMachineFixture.State.DONE;
import static io.github.graphmaquina.fixtures.TicketMachineFixture.State.OPEN;
import static io.github.graphmaquina.fixtures.TicketMachineFixture.State.REVIEW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.IgnoreReason;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.RejectionKind;
import io.github.graphmaquina.api.TransitionKind;
import io.github.graphmaquina.api.TransitionRecord;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.fixtures.TicketMachineFixture.Id;
import io.github.graphmaquina.fixtures.TicketMachineFixture.State;
import io.github.graphmaquina.fixtures.TicketMachineFixture.Ticket;
import io.github.graphmaquina.fixtures.TicketMachineFixture;
import io.github.graphmaquina.runtime.MachineRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Running machines by identifier: the selected parallel edge, rejections and their reasons, self-loops, the
 * input of an invocation, moves and their records, reevaluation without a watch, and structural inspection.
 */
class GraphRuntimeTest {

    @Test void invokesTheSelectedParallelTransition() {
        GraphDefinition<Ticket,State,Id> graph = TicketMachineFixture.builder().transition(AUTO_APPROVE, REVIEW, DONE)
            .action("auto", ctx -> { ctx.entity().calls.add("auto"); return ActionOutcome.ok(); })
            .endTransition().build();
        try (MachineRegistry registry = TicketMachineFixture.registry(graph)) {
            Ticket ticket = new Ticket(1, REVIEW);
            Outcome<Ticket,State,Id> result = registry.of(KEY).execute(ticket, AUTO_APPROVE);
            assertInstanceOf(Outcome.Applied.class, result);
            assertEquals(List.of("auto"), ticket.calls);
            assertEquals(Optional.of(AUTO_APPROVE), result.requestedTransition());
        }
    }

    @Test void missingTransitionDoesNotRunAnotherOperation() {
        try (MachineRegistry registry = TicketMachineFixture.registry(TicketMachineFixture.builder().build())) {
            Ticket ticket = new Ticket(1, OPEN);
            Outcome<Ticket,State,Id> result = registry.of(KEY).execute(ticket, AUTO_APPROVE);
            assertInstanceOf(Outcome.Rejected.class, result);
            assertEquals(OPEN, result.recordedState());
            assertTrue(ticket.calls.isEmpty());
        }
    }

    @Test void explicitSelfLoopRunsItsActionAndHook() {
        GraphDefinition<Ticket,State,Id> graph = TicketMachineFixture.builder().allowsCycle(REVIEW)
            .transition(RETOUCH, REVIEW, REVIEW)
            .action("touch", ctx -> { ctx.entity().calls.add("touch"); return ActionOutcome.ok(); })
            .afterCommitWith("touched", (ticket, commit) -> ticket.calls.add(commit.transition().name()))
            .endTransition().build();
        try (MachineRegistry registry = TicketMachineFixture.registry(graph)) {
            Ticket ticket = new Ticket(1, REVIEW);
            Outcome<Ticket,State,Id> result = registry.of(KEY).execute(ticket, RETOUCH);
            assertInstanceOf(Outcome.Applied.class, result);
            assertEquals(List.of("touch", "RETOUCH"), ticket.calls);
            assertEquals(REVIEW, ticket.state);
        }
    }

    @Test void inputIsPerInvocationAndSurvivesRecovery() {
        Object payload = new Object();
        List<Optional<?>> seen = new ArrayList<>();
        GraphDefinition<Ticket,State,Id> graph = StateGraph.define(KEY)
            .initial(OPEN).terminal(DONE)
            .statePolicy(OPEN, "submit first").statePolicy(REVIEW, "review required")
            .transition(SUBMIT, OPEN, REVIEW).endTransition()
            .transition(APPROVE, REVIEW, DONE)
                .guard("inputGuard", ctx -> { seen.add(ctx.input()); return GuardResult.pass(); })
                .action("failed", ctx -> { seen.add(ctx.input()); return ActionOutcome.failed("declined"); })
                .onFailure(FAIL).endTransition()
            .transition(FAIL, REVIEW, DONE)
                .action("recover", ctx -> { seen.add(ctx.input()); return ActionOutcome.ok(); })
                .endTransition().build();
        try (MachineRegistry registry = TicketMachineFixture.registry(graph)) {
            Outcome<Ticket,State,Id> outcome = registry.of(KEY).execute(new Ticket(1, REVIEW), APPROVE, payload);
            assertInstanceOf(Outcome.Failed.class, outcome);
            assertEquals(3, seen.size());
            for (Optional<?> input : seen) assertSame(payload, input.orElseThrow());
            seen.clear();
            registry.of(KEY).execute(new Ticket(2, REVIEW), FAIL);
            assertEquals(List.of(Optional.empty()), seen);
        }
    }

    /** A rejection carries its kind, the state's reason, no guard, and the identifier the request asked for. */
    @Test void aRejectionCarriesItsKindAndTheRequestedIdentity() {
        try (MachineRegistry registry = TicketMachineFixture.registry(TicketMachineFixture.builder().build())) {
            Outcome<Ticket,State,Id> result = registry.of(KEY).execute(new Ticket(1, OPEN), APPROVE);
            assertInstanceOf(Outcome.Rejected.class, result);
            Outcome.Rejected<Ticket,State,Id> rejected = (Outcome.Rejected<Ticket,State,Id>) result;
            assertEquals(RejectionKind.NO_TRANSITION, rejected.kind());
            assertEquals("submit first", rejected.reason());
            assertEquals(Optional.empty(), rejected.guard());
            assertEquals(Optional.of(APPROVE), rejected.requestedTransition());
        }
    }

    /** A specific reason overrides the state's default one, and only for identifiers with no edge. */
    @Test void aDeclaredRejectionReasonWinsOverThePolicyDefault() {
        GraphDefinition<Ticket,State,Id> graph = TicketMachineFixture.builder()
            .reject(OPEN, APPROVE, "approval requires review").build();
        try (MachineRegistry registry = TicketMachineFixture.registry(graph)) {
            Outcome<Ticket,State,Id> approve = registry.of(KEY).execute(new Ticket(1, OPEN), APPROVE);
            assertEquals("approval requires review", ((Outcome.Rejected<Ticket,State,Id>) approve).reason());
            Outcome<Ticket,State,Id> auto = registry.of(KEY).execute(new Ticket(2, OPEN), AUTO_APPROVE);
            assertEquals("submit first", ((Outcome.Rejected<Ticket,State,Id>) auto).reason());
        }
    }

    /** An edge with no action is applied straight after its guards, and it records the identity that selected it. */
    @Test void aMoveIsAppliedAndRecordsItsIdentity() {
        try (MachineRegistry registry = TicketMachineFixture.registry(TicketMachineFixture.builder().build())) {
            Ticket ticket = new Ticket(1, OPEN);
            Outcome<Ticket,State,Id> result = registry.of(KEY).execute(ticket, SUBMIT);
            assertInstanceOf(Outcome.Applied.class, result);
            Outcome.Applied<Ticket,State,Id> applied = (Outcome.Applied<Ticket,State,Id>) result;
            assertEquals(REVIEW, ticket.state);
            assertEquals(1, applied.records().size());
            TransitionRecord<?,?,?> record = applied.records().get(0);
            assertEquals(TransitionKind.MOVE, record.kind());
            assertEquals(SUBMIT, record.transition());
            assertEquals(Optional.empty(), record.action());
            assertEquals(KEY, record.key());
        }
    }

    /** A guard that rejects makes the call a GUARD rejection that names it; the action never runs. */
    @Test void aGuardRejectionNamesTheGuardAndSkipsTheAction() {
        GraphDefinition<Ticket,State,Id> graph = TicketMachineFixture.builder()
            .transition(AUTO_APPROVE, REVIEW, DONE)
                .guard("never", ctx -> GuardResult.reject("not automatic"))
                .action("auto", ctx -> { ctx.entity().calls.add("auto"); return ActionOutcome.ok(); })
                .endTransition().build();
        try (MachineRegistry registry = TicketMachineFixture.registry(graph)) {
            Ticket ticket = new Ticket(1, REVIEW);
            Outcome<Ticket,State,Id> result = registry.of(KEY).execute(ticket, AUTO_APPROVE);
            assertInstanceOf(Outcome.Rejected.class, result);
            Outcome.Rejected<Ticket,State,Id> rejected = (Outcome.Rejected<Ticket,State,Id>) result;
            assertEquals(RejectionKind.GUARD, rejected.kind());
            assertEquals(Optional.of("never"), rejected.guard());
            assertEquals("not automatic", rejected.reason());
            assertTrue(ticket.calls.isEmpty());
            assertEquals(REVIEW, ticket.state);
        }
    }

    /** Reevaluation without a watch is Ignored and says so; it is not a rejection and selects nothing. */
    @Test void reevaluationWithoutAWatchIsIgnoredWithoutARequest() {
        try (MachineRegistry registry = TicketMachineFixture.registry(TicketMachineFixture.builder().build())) {
            Outcome<Ticket,State,Id> result = registry.of(KEY).reevaluate(new Ticket(1, OPEN));
            assertInstanceOf(Outcome.Ignored.class, result);
            Outcome.Ignored<Ticket,State,Id> ignored = (Outcome.Ignored<Ticket,State,Id>) result;
            assertEquals(IgnoreReason.NO_WATCH, ignored.reason());
            assertEquals(Optional.empty(), ignored.requestedTransition());
            assertEquals(OPEN, ignored.recordedState());
        }
    }

    /**
     * Structural inspection returns the identifiers declared from a state, not destinations, and evaluates no guard:
     * AUTO_APPROVE is listed although its guard would reject, and that guard never runs. A terminal gives none.
     */
    @Test void transitionsFromListsIdentifiers() {
        List<String> evaluated = new ArrayList<>();
        GraphDefinition<Ticket,State,Id> graph = TicketMachineFixture.builder()
            .transition(AUTO_APPROVE, REVIEW, DONE)
                .guard("never evaluated", (GuardContext<Ticket, State, Id> ctx) -> {
                    evaluated.add("never evaluated");
                    return GuardResult.reject("transitionsFrom evaluated a guard");
                })
                .endTransition().build();
        try (MachineRegistry registry = TicketMachineFixture.registry(graph)) {
            assertEquals(Set.of(APPROVE, AUTO_APPROVE), registry.of(KEY).transitionsFrom(REVIEW));
            assertEquals(Set.of(SUBMIT), registry.of(KEY).transitionsFrom(OPEN));
            assertEquals(Set.of(), registry.of(KEY).transitionsFrom(DONE));
            assertEquals(List.of(), evaluated);
        }
    }
}
