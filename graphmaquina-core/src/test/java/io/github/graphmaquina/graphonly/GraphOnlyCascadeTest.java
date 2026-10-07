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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.error.CascadeCycleException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.error.UnhandledFailureException;
import io.github.graphmaquina.fixtures.GraphOnlyFixture.Id;
import io.github.graphmaquina.fixtures.GraphOnlyFixture.State;
import io.github.graphmaquina.fixtures.GraphOnlyFixture.Ticket;
import io.github.graphmaquina.fixtures.GraphOnlyFixture;
import io.github.graphmaquina.runtime.MachineRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Cascades and failure routes over graph-only machines: the recovering edge taken as declared, parallel edges that do
 * not merge, cycles by identifier, the pending state between cascades and after a failure route, hooks of reverted
 * edges, a cascade with no edge, and a failure with no route.
 */
class GraphOnlyCascadeTest {

    /**
     * The peer the tests that cascade to a second entity drive; the actions reach it through this field, not through
     * a lookup.
     */
    final Ticket peer = new Ticket(9, OPEN);

    @Test void onlyTheRecoveringEdgeCommitsItsActionAndHook() {
        GraphDefinition<Ticket,State,Id> graph = StateGraph.define(KEY).initial(OPEN).terminal(DONE)
            .statePolicy(OPEN, "submit first").statePolicy(REVIEW, "review required")
            .transition(SUBMIT, OPEN, REVIEW).endTransition()
            .transition(APPROVE, REVIEW, DONE)
                .action("approve", ctx -> ActionOutcome.failed("declined"))
                .onFailure(FAIL).endTransition()
            .transition(FAIL, REVIEW, DONE)
                .action("recover", ctx -> { ctx.entity().calls.add("recover"); return ActionOutcome.ok(); })
                .afterCommitWith("recovered", (ticket, commit) -> ticket.calls.add(commit.transition().name()))
                .endTransition().build();
        try (MachineRegistry registry = GraphOnlyFixture.registry(graph)) {
            Ticket ticket = new Ticket(1, REVIEW);
            Outcome<Ticket,State,Id> result = registry.of(KEY).execute(ticket, APPROVE);
            assertInstanceOf(Outcome.Failed.class, result);
            assertEquals(List.of("recover", "FAIL"), ticket.calls);
            assertEquals(DONE, result.recordedState());
        }
    }

    /** The recovering edge is taken as declared: its guards are skipped and its own onFailure is not chained. */
    @Test void theRecoveringEdgeSkipsItsGuardsAndDoesNotChainItsOwnRoute() {
        List<String> guards = new ArrayList<>();
        GraphDefinition<Ticket,State,Id> graph = StateGraph.define(KEY).initial(OPEN).terminal(DONE)
                .statePolicy(OPEN, "submit first").statePolicy(REVIEW, "review required")
                .transition(SUBMIT, OPEN, REVIEW).endTransition()
                .transition(APPROVE, REVIEW, DONE)
                    .action("approve", ctx -> ActionOutcome.failed("declined"))
                    .onFailure(FAIL).endTransition()
                .transition(FAIL, REVIEW, DONE)
                    .guard("never", ctx -> { guards.add("never"); return GuardResult.reject("blocked"); })
                    .action("recover", ctx -> ActionOutcome.failed("cannot recover either"))
                    .onFailure(AUTO_APPROVE).endTransition()
                .transition(AUTO_APPROVE, REVIEW, DONE)
                    .action("auto", ctx -> { ctx.entity().calls.add("auto"); return ActionOutcome.ok(); })
                    .endTransition().build();
        try (MachineRegistry registry = GraphOnlyFixture.registry(graph)) {
            Ticket ticket = new Ticket(1, REVIEW);
            TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                    () -> registry.of(KEY).execute(ticket, APPROVE));
            assertTrue(error.getMessage().contains("failure route"));
            assertEquals(List.of(), guards);
            assertEquals(List.of(), ticket.calls);
            assertEquals(REVIEW, ticket.state);
        }
    }

    /** Two identifiers reaching the same state are two edges: asking for one never runs the other. */
    @Test void twoIdentifiersToTheSameDestinationDoNotMerge() {
        GraphDefinition<Ticket,State,Id> graph = GraphOnlyFixture.builder()
                .transition(AUTO_APPROVE, REVIEW, DONE)
                    .action("auto", ctx -> { ctx.entity().calls.add("auto"); return ActionOutcome.ok(); })
                    .endTransition().build();
        try (MachineRegistry registry = GraphOnlyFixture.registry(graph)) {
            Ticket manual = new Ticket(1, REVIEW);
            Ticket automatic = new Ticket(2, REVIEW);
            registry.of(KEY).execute(manual, APPROVE);
            registry.of(KEY).execute(automatic, AUTO_APPROVE);
            assertEquals(List.of("manual"), manual.calls);
            assertEquals(List.of("auto"), automatic.calls);
            assertEquals(DONE, manual.state);
            assertEquals(DONE, automatic.state);
        }
    }

    /** No convergence: asking again for the very identifier already in flight is still a cycle. */
    @Test void recursionOnAnEntityInFlightFailsEvenWithTheSameIdentifier() {
        GraphDefinition<Ticket,State,Id> graph = StateGraph.define(KEY).initial(OPEN).terminal(DONE)
                .statePolicy(OPEN, "submit first").statePolicy(REVIEW, "review required")
                .transition(SUBMIT, OPEN, REVIEW)
                    .action("submit", ctx -> { ctx.cascade(KEY, ctx.entity(), SUBMIT); return ActionOutcome.ok(); })
                    .endTransition()
                .transition(APPROVE, REVIEW, DONE).endTransition().build();
        try (MachineRegistry registry = GraphOnlyFixture.registry(graph)) {
            Ticket ticket = new Ticket(1, OPEN);
            CascadeCycleException error = assertThrows(CascadeCycleException.class,
                    () -> registry.of(KEY).execute(ticket, SUBMIT));
            assertEquals(SUBMIT, error.inFlightTransition());
            assertEquals(SUBMIT, error.requestedTransition());
            assertEquals(OPEN, ticket.state);
        }
    }

    /** A recovery replaces the failed edge as the identity currently executing for the entity. */
    @Test void recursionDuringRecoveryReportsTheRecoveringIdentity() {
        GraphDefinition<Ticket,State,Id> graph = StateGraph.define(KEY).initial(OPEN).terminal(DONE)
                .statePolicy(OPEN, "submit first").statePolicy(REVIEW, "review required")
                .transition(SUBMIT, OPEN, REVIEW).endTransition()
                .transition(APPROVE, REVIEW, DONE).action("approve", ctx -> ActionOutcome.failed("declined"))
                    .onFailure(FAIL).endTransition()
                .transition(FAIL, REVIEW, DONE).action("recover", ctx -> {
                    assertEquals(FAIL, ctx.transition());
                    ctx.cascade(KEY, ctx.entity(), FAIL);
                    return ActionOutcome.ok();
                }).afterCommit("hook", ticket -> ticket.calls.add("hook")).endTransition().build();
        try (MachineRegistry registry = GraphOnlyFixture.registry(graph)) {
            Ticket ticket = new Ticket(1, REVIEW);
            CascadeCycleException error = assertThrows(CascadeCycleException.class,
                    () -> registry.of(KEY).execute(ticket, APPROVE));
            assertEquals(FAIL, error.inFlightTransition());
            assertEquals(FAIL, error.requestedTransition());
            assertEquals(List.of(APPROVE, FAIL), error.journal().stream().map(record -> record.transition()).toList());
            assertEquals(REVIEW, ticket.state);
            assertEquals(List.of(), ticket.calls);
        }
    }

    /** Once a cascade has finished, the peer's pending state is what the next request resolves against. */
    @Test void afterACascadeHasFinishedTheNextOneResolvesFromThePendingState() {
        List<String> steps = new ArrayList<>();
        GraphDefinition<Ticket,State,Id> graph = StateGraph.define(KEY).initial(OPEN).terminal(DONE)
                .statePolicy(OPEN, "submit first").statePolicy(REVIEW, "review required")
                .transition(SUBMIT, OPEN, REVIEW)
                    .action("submit", ctx -> { steps.add("submit@" + ctx.depth()); return ActionOutcome.ok(); })
                    .endTransition()
                .transition(APPROVE, REVIEW, DONE)
                    .action("approve", ctx -> {
                        if (ctx.depth() == 0) {
                            ctx.cascade(KEY, peer, SUBMIT);
                            ctx.cascade(KEY, peer, APPROVE);
                        } else {
                            steps.add("approve@" + ctx.depth());
                        }
                        return ActionOutcome.ok();
                    }).endTransition().build();
        try (MachineRegistry registry = GraphOnlyFixture.registry(graph)) {
            Ticket owner = new Ticket(1, REVIEW);
            assertInstanceOf(Outcome.Applied.class, registry.of(KEY).execute(owner, APPROVE));
            assertEquals(List.of("submit@1", "approve@1"), steps);
            assertEquals(DONE, peer.state);
            assertEquals(DONE, owner.state);
        }
    }

    /** A cascade rolled back by the caller's failure neither moves the peer nor runs its hook. */
    @Test void hooksOfRevertedEdgesDoNotRunAndRecoveryResolvesFromTheOriginalOrigin() {
        List<String> hooks = new ArrayList<>();
        GraphDefinition<Ticket,State,Id> graph = StateGraph.define(KEY).initial(OPEN).terminal(DONE)
                .statePolicy(OPEN, "submit first").statePolicy(REVIEW, "review required")
                .transition(SUBMIT, OPEN, REVIEW)
                    .afterCommit("submitted", ticket -> hooks.add("submitted:" + ticket.id)).endTransition()
                .transition(APPROVE, REVIEW, DONE)
                    .action("approve", ctx -> {
                        ctx.cascade(KEY, peer, SUBMIT);
                        return ActionOutcome.failed("declined");
                    })
                    .onFailure(FAIL).endTransition()
                .transition(FAIL, REVIEW, DONE)
                    .action("recover", ctx -> { ctx.entity().calls.add("recover from " + ctx.from()); return ActionOutcome.ok(); })
                    .afterCommit("recovered", ticket -> hooks.add("recovered:" + ticket.id)).endTransition()
                .build();
        try (MachineRegistry registry = GraphOnlyFixture.registry(graph)) {
            Ticket owner = new Ticket(1, REVIEW);
            Outcome<Ticket,State,Id> result = registry.of(KEY).execute(owner, APPROVE);
            assertInstanceOf(Outcome.Failed.class, result);
            assertEquals(List.of("recover from REVIEW"), owner.calls);
            assertEquals(OPEN, peer.state);
            assertEquals(DONE, owner.state);
            assertEquals(List.of("recovered:1"), hooks);
        }
    }

    /** A cascade that resolves no edge aborts the whole transaction rather than picking another route. */
    @Test void aCascadeWithNoEdgeAbortsTheWholeTransaction() {
        GraphDefinition<Ticket,State,Id> graph = StateGraph.define(KEY).initial(OPEN).terminal(DONE)
                .statePolicy(OPEN, "submit first").statePolicy(REVIEW, "review required")
                .transition(SUBMIT, OPEN, REVIEW).endTransition()
                .transition(APPROVE, REVIEW, DONE)
                    .action("approve", ctx -> { ctx.cascade(KEY, peer, APPROVE); return ActionOutcome.ok(); })
                    .endTransition().build();
        try (MachineRegistry registry = GraphOnlyFixture.registry(graph)) {
            Ticket owner = new Ticket(1, REVIEW);
            TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                    () -> registry.of(KEY).execute(owner, APPROVE));
            assertTrue(error.getMessage().contains("rejected inside a cascade"));
            assertEquals(REVIEW, owner.state);
            assertEquals(OPEN, peer.state);
        }
    }

    /** A failure with no route at all is unhandled, and nothing is written. */
    @Test void aFailureWithoutARouteIsUnhandled() {
        GraphDefinition<Ticket,State,Id> graph = GraphOnlyFixture.builder()
                .transition(AUTO_APPROVE, REVIEW, DONE)
                    .action("auto", ctx -> ActionOutcome.failed("declined")).endTransition().build();
        try (MachineRegistry registry = GraphOnlyFixture.registry(graph)) {
            Ticket ticket = new Ticket(1, REVIEW);
            assertThrows(UnhandledFailureException.class, () -> registry.of(KEY).execute(ticket, AUTO_APPROVE));
            assertEquals(REVIEW, ticket.state);
        }
    }

    /**
     * A failure route restores what the discarded child had left pending on a peer, not the peer's committed
     * state: the recovery reads REVIEW, which no entity holds yet, and the discarded hop runs no hook.
     */
    @Test void aFailureRouteRestoresThePendingStateAnEarlierCascadeGaveThePeer() {
        List<String> hooks = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        Ticket child = new Ticket(2, REVIEW);
        GraphDefinition<Ticket,State,Id> graph = StateGraph.define(KEY).initial(OPEN).terminal(DONE)
                .statePolicy(OPEN, "submit first").statePolicy(REVIEW, "review required")
                .transition(SUBMIT, OPEN, REVIEW)
                    .afterCommit("submitted", ticket -> hooks.add("submitted:" + ticket.id)).endTransition()
                .transition(APPROVE, REVIEW, DONE)
                    .action("approve", ctx -> {
                        if (ctx.depth() == 0) {
                            ctx.cascade(KEY, peer, SUBMIT);
                            ctx.cascade(KEY, child, APPROVE);
                            return ActionOutcome.ok();
                        }
                        ctx.cascade(KEY, peer, AUTO_APPROVE);
                        return ActionOutcome.failed("declined");
                    })
                    .afterCommit("approved", ticket -> hooks.add("approved:" + ticket.id))
                    .onFailure(FAIL).endTransition()
                .transition(AUTO_APPROVE, REVIEW, DONE)
                    .afterCommit("auto", ticket -> hooks.add("auto:" + ticket.id)).endTransition()
                .transition(FAIL, REVIEW, DONE)
                    .action("recover", ctx -> { seen.add(ctx.peer(KEY).stateOf(peer).name()); return ActionOutcome.ok(); })
                    .afterCommit("recovered", ticket -> hooks.add("recovered:" + ticket.id)).endTransition()
                .build();
        try (MachineRegistry registry = GraphOnlyFixture.registry(graph)) {
            Ticket owner = new Ticket(1, REVIEW);
            Outcome<Ticket,State,Id> result = registry.of(KEY).execute(owner, APPROVE);
            assertInstanceOf(Outcome.Applied.class, result);
            Outcome.Applied<Ticket,State,Id> applied = (Outcome.Applied<Ticket,State,Id>) result;
            assertEquals(List.of("REVIEW"), seen);
            assertEquals(DONE, owner.state);
            assertEquals(DONE, child.state);
            assertEquals(REVIEW, peer.state);
            assertEquals(List.of("approved:1", "submitted:9", "recovered:2"), hooks);
            assertEquals(List.of(APPROVE, SUBMIT, FAIL),
                    applied.records().stream().map(record -> record.transition()).toList());
        }
    }
}
