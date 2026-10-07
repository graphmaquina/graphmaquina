package io.github.graphmaquina.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.ActionRecord;
import io.github.graphmaquina.api.CascadePolicy;
import io.github.graphmaquina.api.FailureKind;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.PeerView;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.TransitionRecord;
import io.github.graphmaquina.dsl.GraphBuilder;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.error.CascadeCycleException;
import io.github.graphmaquina.error.CascadeDepthExceededException;
import io.github.graphmaquina.error.CascadeException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.fixtures.RecoveryFixtures;
import io.github.graphmaquina.fixtures.RecoveryFixtures.InvalidResponseException;
import io.github.graphmaquina.fixtures.RecoveryFixtures.PublicationFailures;
import io.github.graphmaquina.fixtures.RecoveryFixtures.ServiceUnavailableException;
import io.github.graphmaquina.fixtures.RecoveryFixtures.Ticket;
import io.github.graphmaquina.fixtures.RecoveryFixtures.TicketChange;
import io.github.graphmaquina.fixtures.RecoveryFixtures.TicketState;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Typed recovery inside a unit of work. A recovery rolls back every hop the failed action cascaded, so only the
 * recovery's own path commits. A peer's routed failure is provisional: the parent action sees its failure kind, and
 * the peer's recovery commits with the root, or goes when the parent recovers or aborts. A poisoned unit of work takes
 * no recovery. A recovery's own cascades follow the current limits: the depth limit, COLLECT with its savepoints and
 * its poison, and the cycle a recovery makes when it cascades back into the ticket in flight.
 */
class CascadeRecoveryTest {

    /** States of a listing, the peer that a ticket's publication puts live. */
    enum ListingState { DRAFT, LIVE, SUSPENDED, WITHDRAWN }

    /** Transition identifiers of a listing. */
    enum ListingChange { GO_LIVE, SUSPEND, WITHDRAW }

    /** A listing: its state, how many times the engine wrote it, and the log its actions and hooks append to. */
    static final class Listing {
        final String id;
        ListingState state = ListingState.DRAFT;
        int stateWrites;
        final List<String> calls = new ArrayList<>();

        Listing(String id) {
            this.id = id;
        }
    }

    static final MachineKey<Listing, ListingState, ListingChange> LISTINGS =
            MachineKey.of(Listing.class, ListingState.class, ListingChange.class);

    /**
     * The publication graph of {@link RecoveryFixtures#graph}, with PUBLISH configured by {@code publish} and
     * SCHEDULE_RETRY by {@code retry}. RECORD_REJECTION and MARK_INTEGRATION_FAILED run an action that logs their
     * name, so that a route nobody expects shows in the ticket's calls.
     */
    private static GraphDefinition<Ticket, TicketState, TicketChange> tickets(
            UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>> publish,
            UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>> retry) {
        GraphBuilder<Ticket, TicketState, TicketChange> builder = StateGraph.define(RecoveryFixtures.KEY)
                .initial(TicketState.READY).terminal(TicketState.PUBLISHED).terminal(TicketState.CLOSED)
                .allowsCycle(TicketState.READY)
                .statePolicy(TicketState.READY, "publish the ticket")
                .statePolicy(TicketState.RETRY_PENDING, "retry the publication explicitly")
                .statePolicy(TicketState.REJECTED_BY_PROVIDER, "close the rejected ticket")
                .statePolicy(TicketState.INTEGRATION_FAILED, "close after manual review");
        GraphBuilder<Ticket, TicketState, TicketChange> published =
                publish.apply(builder.transition(TicketChange.PUBLISH, TicketState.READY, TicketState.PUBLISHED))
                        .endTransition();
        return retry.apply(published.transition(TicketChange.SCHEDULE_RETRY, TicketState.READY,
                        TicketState.RETRY_PENDING))
                .endTransition()
                .transition(TicketChange.RECORD_REJECTION, TicketState.READY, TicketState.REJECTED_BY_PROVIDER)
                        .action("record rejection", logsOnTicket("record rejection")).endTransition()
                .transition(TicketChange.MARK_INTEGRATION_FAILED, TicketState.READY, TicketState.INTEGRATION_FAILED)
                        .action("mark integration failed", logsOnTicket("mark integration failed")).endTransition()
                .transition(TicketChange.RETRY, TicketState.RETRY_PENDING, TicketState.READY).endTransition()
                .transition(TicketChange.CLOSE, TicketState.REJECTED_BY_PROVIDER, TicketState.CLOSED).endTransition()
                .transition(TicketChange.CLOSE, TicketState.INTEGRATION_FAILED, TicketState.CLOSED).endTransition()
                .build();
    }

    /**
     * DRAFT -GO_LIVE-> LIVE runs {@code goLive}, and a failure of the kind Rejected recovers through
     * DRAFT -SUSPEND-> SUSPENDED, which runs {@code suspend}; each of the two logs from a hook once committed. LIVE
     * and SUSPENDED are only withdrawn, so a LIVE listing has no SUSPEND.
     */
    private static GraphDefinition<Listing, ListingState, ListingChange> listings(
            TransitionAction<Listing, ListingState, ListingChange> goLive,
            TransitionAction<Listing, ListingState, ListingChange> suspend) {
        return StateGraph.define(LISTINGS)
                .initial(ListingState.DRAFT).terminal(ListingState.WITHDRAWN)
                .statePolicy(ListingState.DRAFT, "a draft listing goes live or is suspended")
                .statePolicy(ListingState.LIVE, "a live listing is only withdrawn")
                .statePolicy(ListingState.SUSPENDED, "a suspended listing is only withdrawn")
                .transition(ListingChange.GO_LIVE, ListingState.DRAFT, ListingState.LIVE)
                        .action("go live", goLive)
                        .recover(PublicationFailures.Rejected.class, ListingChange.SUSPEND)
                        .afterCommit("went live", (Listing listing) -> listing.calls.add("went live"))
                        .endTransition()
                .transition(ListingChange.SUSPEND, ListingState.DRAFT, ListingState.SUSPENDED)
                        .action("suspend", suspend)
                        .afterCommit("suspended", (Listing listing) -> listing.calls.add("suspended"))
                        .endTransition()
                .transition(ListingChange.WITHDRAW, ListingState.LIVE, ListingState.WITHDRAWN).endTransition()
                .transition(ListingChange.WITHDRAW, ListingState.SUSPENDED, ListingState.WITHDRAWN).endTransition()
                .build();
    }

    /**
     * A registry of the ticket machine, whose setter counts each write as {@link RecoveryFixtures#machine}'s does,
     * and of the listing machine, whose setter counts its own; cascades go at most {@code maxCascadeDepth} deep.
     */
    private static MachineRegistry registry(GraphDefinition<Ticket, TicketState, TicketChange> tickets,
                                            GraphDefinition<Listing, ListingState, ListingChange> listings,
                                            int maxCascadeDepth) {
        return MachineRegistry.builder()
                .add(StateMachines.from(tickets)
                        .state((Ticket ticket) -> ticket.state, (Ticket ticket, TicketState state) -> {
                            ticket.state = state;
                            ticket.stateWrites++;
                        })
                        .identity((Ticket ticket) -> ticket.id)
                        .asyncTimeout(Duration.ofSeconds(5)))
                .add(StateMachines.from(listings)
                        .state((Listing listing) -> listing.state, (Listing listing, ListingState state) -> {
                            listing.state = state;
                            listing.stateWrites++;
                        })
                        .identity((Listing listing) -> listing.id))
                .maxCascadeDepth(maxCascadeDepth)
                .build();
    }

    /** A synchronous ticket action that logs {@code name} on the ticket and succeeds. */
    private static TransitionAction<Ticket, TicketState, TicketChange> logsOnTicket(String name) {
        return (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> {
            ctx.entity().calls.add(name);
            return ActionOutcome.ok();
        };
    }

    /** A synchronous listing action that logs {@code name} on the listing and succeeds. */
    private static TransitionAction<Listing, ListingState, ListingChange> logsOnListing(String name) {
        return (TransitionContext<Listing, ListingState, ListingChange> ctx) -> {
            ctx.entity().calls.add(name);
            return ActionOutcome.ok();
        };
    }

    /**
     * PUBLISH with the rules of the spec's example and a synchronous action that logs "publish" and returns a failure
     * of the kind Unavailable, whose rule selects SCHEDULE_RETRY.
     */
    private static UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>> unavailable() {
        return (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> RecoveryFixtures.exampleRules(
                publish.action("publish", (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> {
                    ctx.entity().calls.add("publish");
                    return ActionOutcome.failed(PublicationFailures.Unavailable.class, "provider busy");
                }));
    }

    /** Each hop the outcome committed, as {@code FROM -ID-> TO KIND action at depth N}. */
    private static List<String> hops(Outcome<?, ?, ?> outcome) {
        List<String> hops = new ArrayList<>();
        for (TransitionRecord<?, ?, ?> record : outcome.records()) {
            hops.add(record.from() + " -" + record.transition() + "-> " + record.to() + " " + record.kind() + " "
                    + record.action().orElse("no action") + " at depth " + record.depth());
        }
        return hops;
    }

    /** Each action of the abort's journal, as {@code name STATUS}. */
    private static List<String> journal(TransitionAbortedException error) {
        return error.journal().stream()
                .map((ActionRecord record) -> record.action() + " " + record.status())
                .toList();
    }

    /** The ticket's state and how many times the engine wrote it. */
    private static String written(Ticket ticket) {
        return "ticket " + ticket.state + " after " + ticket.stateWrites + " write(s)";
    }

    /** The listing's id, state and how many times the engine wrote it. */
    private static String written(Listing listing) {
        return listing.id + " " + listing.state + " after " + listing.stateWrites + " write(s)";
    }

    /**
     * The failed PUBLISH had cascaded GO_LIVE to its listing, which applied, before it failed by returning the kind
     * Unavailable or by throwing the type its rule lists. The recovery rolls that hop back: SCHEDULE_RETRY reads the
     * listing in DRAFT again, so its own cascade, SUSPEND, which a LIVE listing does not have, applies. The call
     * commits the recovery's path alone; the discarded hop is never written and its hook never runs.
     */
    @Test
    void aRecoveryRollsBackTheHopsTheFailedActionCascaded() {
        Map<String, Function<TransitionContext<Ticket, TicketState, TicketChange>, ActionOutcome>> failures =
                new LinkedHashMap<>();
        failures.put("returns the kind", (TransitionContext<Ticket, TicketState, TicketChange> ctx) ->
                ActionOutcome.failed(PublicationFailures.Unavailable.class, "provider busy"));
        failures.put("throws the type", (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> {
            throw new ServiceUnavailableException("provider down");
        });
        for (Map.Entry<String, Function<TransitionContext<Ticket, TicketState, TicketChange>, ActionOutcome>> failure
                : failures.entrySet()) {
            Listing listing = new Listing("l1");
            List<String> seen = new ArrayList<>();
            GraphDefinition<Ticket, TicketState, TicketChange> ticketGraph = tickets(
                    (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> RecoveryFixtures.exampleRules(
                            publish.action("publish", (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> {
                                seen.add("PUBLISH cascaded GO_LIVE: "
                                        + ctx.cascade(LISTINGS, listing, ListingChange.GO_LIVE).code());
                                return failure.getValue().apply(ctx);
                            })),
                    (TransitionBuilder<Ticket, TicketState, TicketChange> retry) -> retry.action("schedule retry",
                            (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> {
                                seen.add("SCHEDULE_RETRY reads " + ctx.peer(LISTINGS).stateOf(listing));
                                seen.add("SCHEDULE_RETRY cascaded SUSPEND: "
                                        + ctx.cascade(LISTINGS, listing, ListingChange.SUSPEND).code());
                                return ActionOutcome.ok();
                            }));
            try (MachineRegistry registry = registry(ticketGraph,
                    listings(logsOnListing("go live"), logsOnListing("suspend")), 10)) {
                Ticket ticket = new Ticket("t1");
                Outcome<Ticket, TicketState, TicketChange> outcome =
                        registry.of(RecoveryFixtures.KEY).execute(ticket, TicketChange.PUBLISH);
                String scenario = failure.getKey();
                assertEquals(Optional.of(PublicationFailures.Unavailable.class),
                        outcome.asFailed().orElseThrow().failureKind(), scenario);
                assertEquals(List.of("PUBLISH cascaded GO_LIVE: APPLIED", "SCHEDULE_RETRY reads DRAFT",
                        "SCHEDULE_RETRY cascaded SUSPEND: APPLIED"), seen, scenario);
                assertEquals(List.of("READY -SCHEDULE_RETRY-> RETRY_PENDING ACTION schedule retry at depth 0",
                        "DRAFT -SUSPEND-> SUSPENDED ACTION suspend at depth 1"), hops(outcome), scenario);
                assertEquals("ticket RETRY_PENDING after 1 write(s)", written(ticket), scenario);
                assertEquals("l1 SUSPENDED after 1 write(s)", written(listing), scenario);
                assertEquals(List.of("go live", "suspend", "suspended"), listing.calls, scenario);
            }
        }
    }

    /**
     * A peer's routed failure is provisional. The listing's GO_LIVE returns the kind Rejected, its own rule takes
     * SUSPEND, and the cascade hands the parent action an Outcome.Failed that carries that kind. The suspension is
     * written only if the root commits it: when the parent applies, the listing ends SUSPENDED; when the parent then
     * fails with the kind Unavailable and recovers, or throws what nothing declares and aborts, the listing is never
     * written.
     */
    @Test
    void aPeersRoutedFailureIsProvisionalAndShowsItsKindToTheParent() {
        Map<String, Function<TransitionContext<Ticket, TicketState, TicketChange>, ActionOutcome>> parents =
                new LinkedHashMap<>();
        parents.put("the parent applies", (TransitionContext<Ticket, TicketState, TicketChange> ctx) ->
                ActionOutcome.ok());
        parents.put("the parent recovers", (TransitionContext<Ticket, TicketState, TicketChange> ctx) ->
                ActionOutcome.failed(PublicationFailures.Unavailable.class, "provider busy"));
        parents.put("the parent aborts", (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> {
            throw new IllegalStateException("the ledger is closed");
        });
        List<String> ends = new ArrayList<>();
        for (Map.Entry<String, Function<TransitionContext<Ticket, TicketState, TicketChange>, ActionOutcome>> parent
                : parents.entrySet()) {
            Listing listing = new Listing("l1");
            List<String> seen = new ArrayList<>();
            GraphDefinition<Ticket, TicketState, TicketChange> ticketGraph = tickets(
                    (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> RecoveryFixtures.exampleRules(
                            publish.action("publish", (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> {
                                Outcome<Listing, ListingState, ListingChange> peer =
                                        ctx.cascade(LISTINGS, listing, ListingChange.GO_LIVE);
                                Outcome.Failed<Listing, ListingState, ListingChange> failed =
                                        peer.asFailed().orElseThrow();
                                seen.add(peer.code() + " " + failed.failureTransition() + " to " + failed.movedTo()
                                        + ", kind " + failed.failureKind().map(Class::getSimpleName).orElse("none")
                                        + ", " + failed.records().size() + " record(s)");
                                return parent.getValue().apply(ctx);
                            })),
                    (TransitionBuilder<Ticket, TicketState, TicketChange> retry) ->
                            retry.action("schedule retry", logsOnTicket("schedule retry")));
            GraphDefinition<Listing, ListingState, ListingChange> listingGraph = listings(
                    (TransitionContext<Listing, ListingState, ListingChange> ctx) ->
                            ActionOutcome.failed(PublicationFailures.Rejected.class, "listing refused"),
                    logsOnListing("suspend"));
            try (MachineRegistry registry = registry(ticketGraph, listingGraph, 10)) {
                Ticket ticket = new Ticket("t1");
                String end;
                try {
                    Outcome<Ticket, TicketState, TicketChange> outcome =
                            registry.of(RecoveryFixtures.KEY).execute(ticket, TicketChange.PUBLISH);
                    end = outcome.code() + " " + hops(outcome);
                } catch (TransitionAbortedException error) {
                    end = error.getClass().getSimpleName() + " caused by "
                            + error.getCause().getClass().getSimpleName();
                }
                ends.add(parent.getKey() + ": saw " + seen + "; " + end + "; " + written(ticket) + "; "
                        + written(listing));
            }
        }
        String saw = "saw [FAILURE_ROUTED SUSPEND to SUSPENDED, kind Rejected, 0 record(s)]";
        assertEquals(List.of(
                "the parent applies: " + saw + "; APPLIED [READY -PUBLISH-> PUBLISHED ACTION publish at depth 0,"
                        + " DRAFT -SUSPEND-> SUSPENDED ACTION suspend at depth 1]; ticket PUBLISHED after 1 write(s);"
                        + " l1 SUSPENDED after 1 write(s)",
                "the parent recovers: " + saw + "; FAILURE_ROUTED [READY -SCHEDULE_RETRY-> RETRY_PENDING ACTION"
                        + " schedule retry at depth 0]; ticket RETRY_PENDING after 1 write(s); l1 DRAFT after 0"
                        + " write(s)",
                "the parent aborts: " + saw + "; TransitionAbortedException caused by IllegalStateException; ticket"
                        + " READY after 0 write(s); l1 DRAFT after 0 write(s)"), ends);
    }

    /**
     * A poisoned unit of work takes no recovery. The listing's GO_LIVE throws what nothing declares, which aborts the
     * peer and poisons the transaction; PUBLISH catches that abort, then returns the kind Unavailable, throws the type
     * a rule lists or throws the type its failsOn lists. Each ends the call with the abort it caught: SCHEDULE_RETRY
     * and the fallback never run, and nothing is written.
     */
    @Test
    void aPoisonedUnitOfWorkTakesNoRecovery() {
        Map<String, Function<TransitionContext<Ticket, TicketState, TicketChange>, ActionOutcome>> thenFails =
                new LinkedHashMap<>();
        thenFails.put("returns the kind", (TransitionContext<Ticket, TicketState, TicketChange> ctx) ->
                ActionOutcome.failed(PublicationFailures.Unavailable.class, "provider busy"));
        thenFails.put("throws the rule's type", (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> {
            throw new ServiceUnavailableException("provider down");
        });
        thenFails.put("throws the failsOn type", (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> {
            throw new InvalidResponseException("unreadable answer");
        });
        List<String> ends = new ArrayList<>();
        List<String> expected = new ArrayList<>();
        for (Map.Entry<String, Function<TransitionContext<Ticket, TicketState, TicketChange>, ActionOutcome>> then
                : thenFails.entrySet()) {
            Listing listing = new Listing("l1");
            AtomicReference<TransitionAbortedException> caught = new AtomicReference<>();
            GraphDefinition<Ticket, TicketState, TicketChange> ticketGraph = tickets(
                    (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> RecoveryFixtures.exampleRules(
                            publish.action("publish", (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> {
                                ctx.entity().calls.add("publish");
                                try {
                                    ctx.cascade(LISTINGS, listing, ListingChange.GO_LIVE);
                                } catch (TransitionAbortedException failure) {
                                    caught.set(failure);
                                }
                                return then.getValue().apply(ctx);
                            })),
                    (TransitionBuilder<Ticket, TicketState, TicketChange> retry) ->
                            retry.action("schedule retry", logsOnTicket("schedule retry")));
            GraphDefinition<Listing, ListingState, ListingChange> listingGraph = listings(
                    (TransitionContext<Listing, ListingState, ListingChange> ctx) -> {
                        throw new IllegalStateException("listing service down");
                    },
                    logsOnListing("suspend"));
            try (MachineRegistry registry = registry(ticketGraph, listingGraph, 10)) {
                Ticket ticket = new Ticket("t1");
                TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                        () -> registry.of(RecoveryFixtures.KEY).execute(ticket, TicketChange.PUBLISH));
                ends.add(then.getKey() + ": " + (error == caught.get() ? "the abort PUBLISH caught" : error)
                        + " caused by " + error.getCause().getClass().getSimpleName() + ", calls " + ticket.calls
                        + "; " + written(ticket) + "; " + written(listing));
                expected.add(then.getKey() + ": the abort PUBLISH caught caused by IllegalStateException, calls"
                        + " [publish]; ticket READY after 0 write(s); l1 DRAFT after 0 write(s)");
            }
        }
        assertEquals(expected, ends);
    }

    /**
     * A recovery's cascade fails: SCHEDULE_RETRY cascades GO_LIVE to a listing whose action throws what nothing
     * declares. Whether the recovery lets that abort through, catches it and succeeds, or catches it and returns a
     * failure its own rule lists, the call ends with that very abort; no other route runs and nothing is written.
     */
    @Test
    void aRecoveryWhoseCascadeFailsEndsTheCallWithThatFailure() {
        Map<String, Function<TransitionAbortedException, ActionOutcome>> recoveries = new LinkedHashMap<>();
        recoveries.put("lets it through", (TransitionAbortedException failure) -> {
            throw failure;
        });
        recoveries.put("catches it and succeeds", (TransitionAbortedException failure) -> ActionOutcome.ok());
        recoveries.put("catches it and fails", (TransitionAbortedException failure) ->
                ActionOutcome.failed(PublicationFailures.Rejected.class, "retry refused"));
        List<String> ends = new ArrayList<>();
        List<String> expected = new ArrayList<>();
        for (Map.Entry<String, Function<TransitionAbortedException, ActionOutcome>> recovery
                : recoveries.entrySet()) {
            Listing listing = new Listing("l1");
            AtomicReference<TransitionAbortedException> received = new AtomicReference<>();
            GraphDefinition<Ticket, TicketState, TicketChange> ticketGraph = tickets(unavailable(),
                    (TransitionBuilder<Ticket, TicketState, TicketChange> retry) -> retry
                            .action("schedule retry", (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> {
                                ctx.entity().calls.add("schedule retry");
                                try {
                                    ctx.cascade(LISTINGS, listing, ListingChange.GO_LIVE);
                                } catch (TransitionAbortedException failure) {
                                    received.set(failure);
                                    return recovery.getValue().apply(failure);
                                }
                                return ActionOutcome.ok();
                            })
                            .recover(PublicationFailures.Rejected.class, TicketChange.RECORD_REJECTION)
                            .onFailure(TicketChange.MARK_INTEGRATION_FAILED));
            GraphDefinition<Listing, ListingState, ListingChange> listingGraph = listings(
                    (TransitionContext<Listing, ListingState, ListingChange> ctx) -> {
                        throw new IllegalStateException("listing service down");
                    },
                    logsOnListing("suspend"));
            try (MachineRegistry registry = registry(ticketGraph, listingGraph, 10)) {
                Ticket ticket = new Ticket("t1");
                TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                        () -> registry.of(RecoveryFixtures.KEY).execute(ticket, TicketChange.PUBLISH));
                ends.add(recovery.getKey() + ": " + (error == received.get() ? "the cascade's abort" : error)
                        + " caused by " + error.getCause().getClass().getSimpleName() + ", calls " + ticket.calls
                        + "; " + written(ticket) + "; " + written(listing));
                expected.add(recovery.getKey() + ": the cascade's abort caused by IllegalStateException, calls"
                        + " [publish, schedule retry]; ticket READY after 0 write(s); l1 DRAFT after 0 write(s)");
            }
        }
        assertEquals(expected, ends);
    }

    /**
     * A recovery's cascades are bounded by the depth limit. With maxCascadeDepth 1, SCHEDULE_RETRY cascades GO_LIVE
     * to a listing, whose action cascades GO_LIVE to another one, a level too deep: the call ends with the depth
     * error, the deeper listing never runs, and nothing is written.
     */
    @Test
    void aRecoveryCascadeIsBoundedByTheDepthLimit() {
        Listing listing = new Listing("l1");
        Listing deeper = new Listing("l2");
        GraphDefinition<Ticket, TicketState, TicketChange> ticketGraph = tickets(unavailable(),
                (TransitionBuilder<Ticket, TicketState, TicketChange> retry) -> retry
                        .action("schedule retry", (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> {
                            ctx.entity().calls.add("schedule retry");
                            ctx.cascade(LISTINGS, listing, ListingChange.GO_LIVE);
                            return ActionOutcome.ok();
                        }));
        GraphDefinition<Listing, ListingState, ListingChange> listingGraph = listings(
                (TransitionContext<Listing, ListingState, ListingChange> ctx) -> {
                    ctx.entity().calls.add("go live");
                    if (ctx.entity() == listing) {
                        ctx.cascade(LISTINGS, deeper, ListingChange.GO_LIVE);
                    }
                    return ActionOutcome.ok();
                },
                logsOnListing("suspend"));
        try (MachineRegistry registry = registry(ticketGraph, listingGraph, 1)) {
            Ticket ticket = new Ticket("t1");
            CascadeDepthExceededException error = assertThrows(CascadeDepthExceededException.class,
                    () -> registry.of(RecoveryFixtures.KEY).execute(ticket, TicketChange.PUBLISH));
            assertEquals(1, error.maxDepth());
            assertEquals(List.of("publish FAILED", "schedule retry THREW", "go live THREW"), journal(error));
            assertEquals(List.of("publish", "schedule retry"), ticket.calls);
            assertEquals(List.of("go live"), listing.calls);
            assertEquals(List.of(), deeper.calls);
            assertEquals("ticket READY after 0 write(s)", written(ticket));
            assertEquals("l1 DRAFT after 0 write(s)", written(listing));
            assertEquals("l2 DRAFT after 0 write(s)", written(deeper));
        }
    }

    /**
     * COLLECT inside a recovery keeps its savepoints and its poison. SCHEDULE_RETRY suspends three listings with
     * COLLECT; the second one's action puts a fourth listing live and then throws. COLLECT rolls back that element,
     * the fourth listing's hop included, keeps the other two, and ends in a CascadeException that poisons the
     * transaction: a peer view taken before the cascade reads exactly that. Whether the recovery lets the aggregate
     * through or catches it and succeeds, the call ends with it, and nothing is written.
     */
    @Test
    void aRecoveryCascadeUnderCollectRollsBackEachFailedElementAndPoisons() {
        for (boolean recoveryCatches : List.of(false, true)) {
            Listing first = new Listing("first");
            Listing failing = new Listing("failing");
            Listing last = new Listing("last");
            Listing touched = new Listing("touched");
            List<String> seen = new ArrayList<>();
            AtomicReference<CascadeException> aggregate = new AtomicReference<>();
            GraphDefinition<Ticket, TicketState, TicketChange> ticketGraph = tickets(unavailable(),
                    (TransitionBuilder<Ticket, TicketState, TicketChange> retry) -> retry
                            .action("schedule retry", (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> {
                                PeerView<Listing, ListingState, ListingChange> view = ctx.peer(LISTINGS);
                                try {
                                    ctx.cascadeAll(LISTINGS, List.of(first, failing, last), ListingChange.SUSPEND,
                                            CascadePolicy.COLLECT);
                                } catch (CascadeException failure) {
                                    aggregate.set(failure);
                                    for (Listing listing : List.of(first, failing, last, touched)) {
                                        seen.add(listing.id + " " + view.stateOf(listing));
                                    }
                                    if (!recoveryCatches) {
                                        throw failure;
                                    }
                                }
                                return ActionOutcome.ok();
                            }));
            GraphDefinition<Listing, ListingState, ListingChange> listingGraph = listings(logsOnListing("go live"),
                    (TransitionContext<Listing, ListingState, ListingChange> ctx) -> {
                        ctx.entity().calls.add("suspend");
                        if (ctx.entity() == failing) {
                            ctx.cascade(LISTINGS, touched, ListingChange.GO_LIVE);
                            throw new IllegalStateException("the listing cannot be suspended");
                        }
                        return ActionOutcome.ok();
                    });
            try (MachineRegistry registry = registry(ticketGraph, listingGraph, 10)) {
                Ticket ticket = new Ticket("t1");
                String scenario = recoveryCatches ? "the recovery catches it" : "the recovery lets it through";
                CascadeException error = assertThrows(CascadeException.class,
                        () -> registry.of(RecoveryFixtures.KEY).execute(ticket, TicketChange.PUBLISH), scenario);
                assertSame(aggregate.get(), error, scenario);
                assertEquals(1, error.errors().size(), scenario);
                assertInstanceOf(IllegalStateException.class, error.errors().get(0).getCause(), scenario);
                assertEquals(List.of("first SUSPENDED", "failing DRAFT", "last SUSPENDED", "touched DRAFT"), seen,
                        scenario);
                assertEquals("ticket READY after 0 write(s)", written(ticket), scenario);
                assertEquals(List.of("first DRAFT after 0 write(s)", "failing DRAFT after 0 write(s)",
                                "last DRAFT after 0 write(s)", "touched DRAFT after 0 write(s)"),
                        List.of(written(first), written(failing), written(last), written(touched)), scenario);
                assertEquals(List.of("suspend"), first.calls, scenario);
                assertEquals(List.of("go live"), touched.calls, scenario);
            }
        }
    }

    /**
     * COLLECT inside a recovery hands it the peers' routed failures with their kind, and commits them with the root.
     * SCHEDULE_RETRY puts two listings live with COLLECT; the second returns the kind Rejected and its rule suspends
     * it. The recovery sees an applied outcome and a routed one of the kind Rejected, and the call commits the
     * recovery, the first listing live and the second suspended.
     */
    @Test
    void aRecoveryCascadeUnderCollectSeesThePeersRoutedFailureWithItsKind() {
        Listing accepted = new Listing("accepted");
        Listing refused = new Listing("refused");
        List<String> seen = new ArrayList<>();
        GraphDefinition<Ticket, TicketState, TicketChange> ticketGraph = tickets(unavailable(),
                (TransitionBuilder<Ticket, TicketState, TicketChange> retry) -> retry
                        .action("schedule retry", (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> {
                            for (Outcome<Listing, ListingState, ListingChange> outcome : ctx.cascadeAll(LISTINGS,
                                    List.of(accepted, refused), ListingChange.GO_LIVE, CascadePolicy.COLLECT)) {
                                Optional<Class<? extends FailureKind>> kind = outcome.asFailed().flatMap(
                                        (Outcome.Failed<Listing, ListingState, ListingChange> failed) ->
                                                failed.failureKind());
                                seen.add(outcome.entity().id + " " + outcome.code() + " "
                                        + kind.map(Class::getSimpleName).orElse("without kind"));
                            }
                            return ActionOutcome.ok();
                        }));
        GraphDefinition<Listing, ListingState, ListingChange> listingGraph = listings(
                (TransitionContext<Listing, ListingState, ListingChange> ctx) -> {
                    ctx.entity().calls.add("go live");
                    return ctx.entity() == refused
                            ? ActionOutcome.failed(PublicationFailures.Rejected.class, "listing refused")
                            : ActionOutcome.ok();
                },
                logsOnListing("suspend"));
        try (MachineRegistry registry = registry(ticketGraph, listingGraph, 10)) {
            Ticket ticket = new Ticket("t1");
            Outcome<Ticket, TicketState, TicketChange> outcome =
                    registry.of(RecoveryFixtures.KEY).execute(ticket, TicketChange.PUBLISH);
            assertEquals(List.of("accepted APPLIED without kind", "refused FAILURE_ROUTED Rejected"), seen);
            assertEquals(List.of("READY -SCHEDULE_RETRY-> RETRY_PENDING ACTION schedule retry at depth 0",
                    "DRAFT -GO_LIVE-> LIVE ACTION go live at depth 1",
                    "DRAFT -SUSPEND-> SUSPENDED ACTION suspend at depth 1"), hops(outcome));
            assertEquals("ticket RETRY_PENDING after 1 write(s)", written(ticket));
            assertEquals("accepted LIVE after 1 write(s)", written(accepted));
            assertEquals("refused SUSPENDED after 1 write(s)", written(refused));
            assertEquals(List.of("go live", "went live"), accepted.calls);
            assertEquals(List.of("go live", "suspend", "suspended"), refused.calls);
        }
    }

    /**
     * Review Focus 2: a recovery that cascades back into the ticket already in flight is a cycle. SCHEDULE_RETRY
     * recovers PUBLISH and asks to PUBLISH its own ticket again; whether it lets the cycle through or catches it and
     * succeeds, the call ends with that cycle, which names SCHEDULE_RETRY as the transition in flight. No other route
     * runs, PUBLISH's fallback included, and nothing is written.
     */
    @Test
    void aRecoveryThatCascadesBackIntoItsTicketAbortsAsACycle() {
        for (boolean recoveryCatches : List.of(false, true)) {
            AtomicReference<CascadeCycleException> cycle = new AtomicReference<>();
            GraphDefinition<Ticket, TicketState, TicketChange> ticketGraph = tickets(unavailable(),
                    (TransitionBuilder<Ticket, TicketState, TicketChange> retry) -> retry
                            .action("schedule retry", (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> {
                                ctx.entity().calls.add("schedule retry");
                                try {
                                    ctx.cascade(RecoveryFixtures.KEY, ctx.entity(), TicketChange.PUBLISH);
                                } catch (CascadeCycleException failure) {
                                    cycle.set(failure);
                                    if (!recoveryCatches) {
                                        throw failure;
                                    }
                                }
                                return ActionOutcome.ok();
                            }));
            try (MachineRegistry registry = registry(ticketGraph,
                    listings(logsOnListing("go live"), logsOnListing("suspend")), 10)) {
                Ticket ticket = new Ticket("t1");
                String scenario = recoveryCatches ? "the recovery catches it" : "the recovery lets it through";
                CascadeCycleException error = assertThrows(CascadeCycleException.class,
                        () -> registry.of(RecoveryFixtures.KEY).execute(ticket, TicketChange.PUBLISH), scenario);
                assertSame(cycle.get(), error, scenario);
                assertEquals(TicketChange.SCHEDULE_RETRY, error.inFlightTransition(), scenario);
                assertEquals(TicketChange.PUBLISH, error.requestedTransition(), scenario);
                assertEquals(List.of("Ticket/TicketState#t1", "Ticket/TicketState#t1"), error.route(), scenario);
                assertEquals(List.of("publish FAILED", "schedule retry THREW"), journal(error), scenario);
                assertEquals(List.of("publish", "schedule retry"), ticket.calls, scenario);
                assertEquals("ticket READY after 0 write(s)", written(ticket), scenario);
            }
        }
    }
}
