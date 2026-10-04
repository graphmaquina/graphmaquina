package io.github.graphmaquina.graphonly;

import static io.github.graphmaquina.fixtures.GraphOnlyFixture.Id.APPROVE;
import static io.github.graphmaquina.fixtures.GraphOnlyFixture.Id.AUTO_APPROVE;
import static io.github.graphmaquina.fixtures.GraphOnlyFixture.Id.SUBMIT;
import static io.github.graphmaquina.fixtures.GraphOnlyFixture.KEY;
import static io.github.graphmaquina.fixtures.GraphOnlyFixture.State.DONE;
import static io.github.graphmaquina.fixtures.GraphOnlyFixture.State.OPEN;
import static io.github.graphmaquina.fixtures.GraphOnlyFixture.State.REVIEW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.IgnoreReason;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.PeerView;
import io.github.graphmaquina.dsl.GraphBuilder;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.fixtures.GraphOnlyFixture.Id;
import io.github.graphmaquina.fixtures.GraphOnlyFixture.State;
import io.github.graphmaquina.fixtures.GraphOnlyFixture.Ticket;
import io.github.graphmaquina.fixtures.GraphOnlyFixture;
import io.github.graphmaquina.runtime.MachineRegistry;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/** A watch rule names the transition it runs, so two edges to the same state stay distinguishable. */
class GraphOnlyWatchTest {

    /** The peer of every watching ticket; the fixture watches its own machine, so the peer is another ticket. */
    final Ticket peer = new Ticket(9, OPEN);

    private GraphDefinition<Ticket,State,Id> watching(Predicate<Ticket> automatic) {
        GraphBuilder<Ticket,State,Id> builder = StateGraph.define(KEY).initial(OPEN).terminal(DONE)
                .statePolicy(OPEN, "submit first").statePolicy(REVIEW, "review required")
                .transition(SUBMIT, OPEN, REVIEW).endTransition()
                .transition(APPROVE, REVIEW, DONE)
                    .action("manual", ctx -> { ctx.entity().calls.add("manual"); return ActionOutcome.ok(); })
                    .endTransition()
                .transition(AUTO_APPROVE, REVIEW, DONE)
                    .action("auto", ctx -> { ctx.entity().calls.add("auto"); return ActionOutcome.ok(); })
                    .endTransition();
        return builder.watch(REVIEW, KEY, (Ticket ticket) -> List.of(peer), (Ticket p) -> Optional.empty())
                .when("automatic", (Ticket ticket, Collection<? extends Ticket> peers,
                        PeerView<Ticket,State,Id> view) -> automatic.test(ticket))
                    .execute(AUTO_APPROVE)
                .when("manual", (Ticket ticket, Collection<? extends Ticket> peers,
                        PeerView<Ticket,State,Id> view) -> true)
                    .execute(APPROVE)
                .endWatch().build();
    }

    @Test void theRuleRunsItsOwnTransitionAndNotTheOtherEdgeToTheSameState() {
        try (MachineRegistry registry = GraphOnlyFixture.registry(watching((Ticket ticket) -> true))) {
            Ticket ticket = new Ticket(1, REVIEW);
            Outcome<Ticket,State,Id> result = registry.of(KEY).reevaluate(ticket);
            assertInstanceOf(Outcome.Applied.class, result);
            assertEquals(List.of("auto"), ticket.calls);
            assertEquals(Optional.of(AUTO_APPROVE), result.requestedTransition());
            assertEquals(DONE, ticket.state);
        }
    }

    @Test void rulesKeepTheirFirstMatchOrder() {
        try (MachineRegistry registry = GraphOnlyFixture.registry(watching((Ticket ticket) -> false))) {
            Ticket ticket = new Ticket(1, REVIEW);
            assertInstanceOf(Outcome.Applied.class, registry.of(KEY).reevaluate(ticket));
            assertEquals(List.of("manual"), ticket.calls);
            assertEquals(DONE, ticket.state);
        }
    }

    /** Ignored distinguishes a state with no watch at all from one whose rules all declined. */
    @Test void ignoredDistinguishesNoWatchFromNoRuleMatched() {
        GraphDefinition<Ticket,State,Id> graph = StateGraph.define(KEY).initial(OPEN).terminal(DONE)
                .statePolicy(OPEN, "submit first").statePolicy(REVIEW, "review required")
                .transition(SUBMIT, OPEN, REVIEW).endTransition()
                .transition(APPROVE, REVIEW, DONE).endTransition()
                .watch(REVIEW, KEY, (Ticket ticket) -> List.of(peer), (Ticket p) -> Optional.empty())
                    .when("never", (Ticket ticket, Collection<? extends Ticket> peers,
                            PeerView<Ticket,State,Id> view) -> false)
                        .execute(APPROVE)
                    .endWatch().build();
        try (MachineRegistry registry = GraphOnlyFixture.registry(graph)) {
            Ticket watched = new Ticket(1, REVIEW);
            Outcome<Ticket,State,Id> noRule = registry.of(KEY).reevaluate(watched);
            assertEquals(IgnoreReason.NO_RULE_MATCHED,
                    ((Outcome.Ignored<Ticket,State,Id>) noRule).reason());

            Ticket unwatched = new Ticket(2, OPEN);
            Outcome<Ticket,State,Id> noWatch = registry.of(KEY).reevaluate(unwatched);
            assertEquals(IgnoreReason.NO_WATCH, ((Outcome.Ignored<Ticket,State,Id>) noWatch).reason());
            assertTrue(watched.calls.isEmpty());
        }
    }

    /**
     * A self-loop is a hop with no net movement. It commits and its action runs, but the watch that would have
     * re-evaluated the owner of the ticket is not notified; the move that follows notifies it, which is what
     * proves the watch was wired to react.
     */
    @Test void aSelfLoopCommitsWithoutNotifyingTheWatchOfItsOwner() {
        Ticket owner = new Ticket(7, REVIEW);
        GraphDefinition<Ticket,State,Id> graph = StateGraph.define(KEY).initial(OPEN).terminal(DONE)
                .allowsCycle(REVIEW)
                .statePolicy(OPEN, "submit first").statePolicy(REVIEW, "review required")
                .transition(SUBMIT, OPEN, REVIEW).endTransition()
                .transition(APPROVE, REVIEW, DONE)
                    .action("manual", ctx -> { ctx.entity().calls.add("manual"); return ActionOutcome.ok(); })
                    .endTransition()
                .transition(Id.RETOUCH, REVIEW, REVIEW)
                    .action("touch", ctx -> { ctx.entity().calls.add("touch"); return ActionOutcome.ok(); })
                    .endTransition()
                .watch(REVIEW, KEY, (Ticket ticket) -> List.of(peer), (Ticket moved) -> Optional.of(owner))
                    .when("always", (Ticket ticket, Collection<? extends Ticket> peers,
                            PeerView<Ticket,State,Id> view) -> true)
                        .execute(APPROVE)
                    .endWatch().build();
        try (MachineRegistry registry = GraphOnlyFixture.registry(graph)) {
            Ticket looping = new Ticket(1, REVIEW);
            Outcome<Ticket,State,Id> result = registry.of(KEY).execute(looping, Id.RETOUCH);
            assertInstanceOf(Outcome.Applied.class, result);
            assertEquals(List.of(), ((Outcome.Applied<Ticket,State,Id>) result).reactions());
            assertEquals(List.of("touch"), looping.calls);
            assertEquals(REVIEW, looping.state);
            assertEquals(REVIEW, owner.state);
            assertTrue(owner.calls.isEmpty());

            Ticket submitted = new Ticket(2, OPEN);
            Outcome<Ticket,State,Id> moved = registry.of(KEY).execute(submitted, SUBMIT);
            assertEquals(1, ((Outcome.Applied<Ticket,State,Id>) moved).reactions().size());
            assertEquals(List.of("manual"), owner.calls);
            assertEquals(DONE, owner.state);
        }
    }

    /** A self-loop selected by a rule is Applied like any other edge; a watch never loops on its own. */
    @Test void aSelfLoopSelectedByAWatchIsApplied() {
        GraphDefinition<Ticket,State,Id> graph = StateGraph.define(KEY).initial(OPEN).terminal(DONE)
                .allowsCycle(REVIEW)
                .statePolicy(OPEN, "submit first").statePolicy(REVIEW, "review required")
                .transition(SUBMIT, OPEN, REVIEW).endTransition()
                .transition(APPROVE, REVIEW, DONE).endTransition()
                .transition(Id.RETOUCH, REVIEW, REVIEW)
                    .action("touch", ctx -> { ctx.entity().calls.add("touch"); return ActionOutcome.ok(); })
                    .endTransition()
                .watch(REVIEW, KEY, (Ticket ticket) -> List.of(peer), (Ticket p) -> Optional.empty())
                    .when("always", (Ticket ticket, Collection<? extends Ticket> peers,
                            PeerView<Ticket,State,Id> view) -> true)
                        .execute(Id.RETOUCH)
                    .endWatch().build();
        try (MachineRegistry registry = GraphOnlyFixture.registry(graph)) {
            Ticket ticket = new Ticket(1, REVIEW);
            Outcome<Ticket,State,Id> result = registry.of(KEY).reevaluate(ticket);
            assertInstanceOf(Outcome.Applied.class, result);
            assertEquals(List.of("touch"), ticket.calls);
            assertEquals(REVIEW, ticket.state);
        }
    }
}
