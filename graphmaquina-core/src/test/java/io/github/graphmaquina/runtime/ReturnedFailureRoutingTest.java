package io.github.graphmaquina.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.ActionRecord;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.FailureKind;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.OutcomeCode;
import io.github.graphmaquina.api.OutcomeStatus;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.StatePolicy;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.TransitionKey;
import io.github.graphmaquina.api.TransitionRecord;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.api.WatchSpec;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.error.UnhandledFailureException;
import io.github.graphmaquina.fixtures.RecoveryFixtures;
import io.github.graphmaquina.fixtures.RecoveryFixtures.PublicationFailures;
import io.github.graphmaquina.fixtures.RecoveryFixtures.Ticket;
import io.github.graphmaquina.fixtures.RecoveryFixtures.TicketChange;
import io.github.graphmaquina.fixtures.RecoveryFixtures.TicketState;
import io.github.graphmaquina.graph.DirectedGraph;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Spec R4: a failure the action returns takes the recovery rule of its failure kind, or else onFailure, which keeps
 * the kind; with neither, the call throws UnhandledFailureException. The kind decides the route only: the outcome is
 * still FAILED and FAILURE_ROUTED, a cascade hands it to the parent action, and a recovery that names no edge from
 * the origin aborts as an onFailure that names none.
 */
class ReturnedFailureRoutingTest {

    /** A failure kind no rule of these graphs declares. */
    record Postponed() implements FailureKind { }

    /** The publication graph with {@code action} on PUBLISH, under the rules and fallback {@code rules} declares. */
    private static GraphDefinition<Ticket, TicketState, TicketChange> publishing(
            TransitionAction<Ticket, TicketState, TicketChange> action,
            UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>> rules) {
        return RecoveryFixtures.graph((TransitionBuilder<Ticket, TicketState, TicketChange> publish) ->
                rules.apply(publish.action("publish", action)));
    }

    /** A PUBLISH action that returns {@code outcome} every time. */
    private static TransitionAction<Ticket, TicketState, TicketChange> returning(ActionOutcome outcome) {
        return (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> outcome;
    }

    /** The two rules of the spec's example, without failsOn types and without a fallback. */
    private static TransitionBuilder<Ticket, TicketState, TicketChange> rulesOnly(
            TransitionBuilder<Ticket, TicketState, TicketChange> publish) {
        return publish
                .recover(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY)
                .recover(PublicationFailures.Rejected.class, TicketChange.RECORD_REJECTION);
    }

    /** Runs PUBLISH on {@code ticket} and narrows the outcome to Failed, failing the test with it otherwise. */
    private static Outcome.Failed<Ticket, TicketState, TicketChange> publishFails(
            StateMachine<Ticket, TicketState, TicketChange> machine, Ticket ticket) {
        Outcome<Ticket, TicketState, TicketChange> outcome = machine.execute(ticket, TicketChange.PUBLISH);
        return outcome.asFailed().orElseThrow(() -> new AssertionError("expected a failed outcome: " + outcome));
    }

    /** R4, first row: no kind, a fallback. */
    @Test
    void aFailureWithoutAKindTakesTheFallbackAndReportsNoKind() {
        Ticket ticket = new Ticket("T-1");
        Outcome.Failed<Ticket, TicketState, TicketChange> failed = publishFails(RecoveryFixtures.machine(
                RecoveryFixtures.exampleGraph(returning(ActionOutcome.failed("invalid response")))), ticket);
        assertEquals(TicketChange.MARK_INTEGRATION_FAILED, failed.failureTransition());
        assertEquals(TicketState.INTEGRATION_FAILED, failed.movedTo());
        assertEquals(Optional.empty(), failed.failureKind());
        assertEquals(Optional.empty(), failed.cause());
        assertEquals("invalid response", failed.reason());
        assertEquals(TicketState.INTEGRATION_FAILED, ticket.state);
    }

    /** R4, second row: no kind and no fallback. Rules never take a failure without a kind. */
    @Test
    void aFailureWithoutAKindAndWithoutAFallbackIsUnhandled() {
        GraphDefinition<Ticket, TicketState, TicketChange> graph = publishing(
                returning(ActionOutcome.failed("invalid response")), ReturnedFailureRoutingTest::rulesOnly);
        Ticket ticket = new Ticket("T-1");
        UnhandledFailureException error = assertThrows(UnhandledFailureException.class,
                () -> RecoveryFixtures.machine(graph).execute(ticket, TicketChange.PUBLISH));
        assertEquals("invalid response", error.reason());
        assertNull(error.getCause());
        assertEquals(CallPhase.ACTION, error.phase());
        assertEquals(TicketState.READY, ticket.state);
        assertEquals(0, ticket.stateWrites);
    }

    /** R4, third row: the rule of the kind takes the failure, whether or not the edge also has a fallback. */
    @Test
    void aFailureOfARulesKindTakesThatRuleWithOrWithoutAFallback() {
        TransitionAction<Ticket, TicketState, TicketChange> action =
                returning(ActionOutcome.failed(PublicationFailures.Unavailable.class, "provider busy"));
        Map<String, GraphDefinition<Ticket, TicketState, TicketChange>> graphs = new LinkedHashMap<>();
        graphs.put("with a fallback", RecoveryFixtures.exampleGraph(action));
        graphs.put("without a fallback", publishing(action, ReturnedFailureRoutingTest::rulesOnly));
        List<String> ends = new ArrayList<>();
        for (Map.Entry<String, GraphDefinition<Ticket, TicketState, TicketChange>> graph : graphs.entrySet()) {
            Ticket ticket = new Ticket("T-1");
            Outcome.Failed<Ticket, TicketState, TicketChange> failed =
                    publishFails(RecoveryFixtures.machine(graph.getValue()), ticket);
            ends.add(graph.getKey() + ": " + failed.failureTransition() + ", "
                    + failed.failureKind().map(Class::getSimpleName).orElse("no kind") + ", " + ticket.state);
        }
        assertEquals(List.of("with a fallback: SCHEDULE_RETRY, Unavailable, RETRY_PENDING",
                "without a fallback: SCHEDULE_RETRY, Unavailable, RETRY_PENDING"), ends);
    }

    /** The spec's example: two kinds, two recoveries, two states, and the requested edge still reported. */
    @Test
    void twoKindsTakeTwoRecoveriesToTwoStates() {
        Ticket unavailable = new Ticket("T-1");
        Ticket rejected = new Ticket("T-2");
        StateMachine<Ticket, TicketState, TicketChange> machine = RecoveryFixtures.machine(
                RecoveryFixtures.exampleGraph((TransitionContext<Ticket, TicketState, TicketChange> ctx) ->
                        ctx.entity() == unavailable
                                ? ActionOutcome.failed(PublicationFailures.Unavailable.class, "provider busy")
                                : ActionOutcome.failed(PublicationFailures.Rejected.class, "content refused")));

        Outcome.Failed<Ticket, TicketState, TicketChange> retry = publishFails(machine, unavailable);
        assertEquals(TicketChange.PUBLISH, retry.transition());
        assertEquals(TicketState.PUBLISHED, retry.to());
        assertEquals(TicketChange.SCHEDULE_RETRY, retry.failureTransition());
        assertEquals(TicketState.RETRY_PENDING, retry.movedTo());
        assertEquals(Optional.of(PublicationFailures.Unavailable.class), retry.failureKind());
        assertEquals(Optional.empty(), retry.cause());
        assertEquals("provider busy", retry.reason());
        assertEquals(TicketState.RETRY_PENDING, unavailable.state);

        Outcome.Failed<Ticket, TicketState, TicketChange> rejection = publishFails(machine, rejected);
        assertEquals(TicketChange.PUBLISH, rejection.transition());
        assertEquals(TicketState.PUBLISHED, rejection.to());
        assertEquals(TicketChange.RECORD_REJECTION, rejection.failureTransition());
        assertEquals(TicketState.REJECTED_BY_PROVIDER, rejection.movedTo());
        assertEquals(Optional.of(PublicationFailures.Rejected.class), rejection.failureKind());
        assertEquals(Optional.empty(), rejection.cause());
        assertEquals(TicketState.REJECTED_BY_PROVIDER, rejected.state);
    }

    /**
     * Runs {@code id} on {@code ticket} and reports where its failure went: the edge, the recovery, the failure kind
     * and the ticket's state afterwards.
     */
    private static String routed(StateMachine<Ticket, TicketState, TicketChange> machine, Ticket ticket,
                                 TicketChange id) {
        TicketState from = ticket.state;
        Outcome<Ticket, TicketState, TicketChange> outcome = machine.execute(ticket, id);
        Outcome.Failed<Ticket, TicketState, TicketChange> failed =
                outcome.asFailed().orElseThrow(() -> new AssertionError("expected a failed outcome: " + outcome));
        return id + " from " + from + ": " + failed.failureTransition() + ", "
                + failed.failureKind().map(Class::getSimpleName).orElse("no kind") + ", " + ticket.state;
    }

    /**
     * Spec §11, "Categorías": a failure kind belongs to no edge and no machine. Two edges of one machine, PUBLISH
     * from READY and RETRY from RETRY_PENDING, and the PUBLISH of another machine declare the same kind, each with a
     * recovery of its own, and a failure of that kind takes the recovery of the edge it failed on.
     */
    @Test
    void oneFailureKindTakesTheRecoveryOfEachEdgeAndMachineThatDeclaresIt() {
        TransitionAction<Ticket, TicketState, TicketChange> unavailable =
                returning(ActionOutcome.failed(PublicationFailures.Unavailable.class, "provider busy"));
        GraphDefinition<Ticket, TicketState, TicketChange> twoEdges = StateGraph.define(RecoveryFixtures.KEY)
                .initial(TicketState.READY).terminal(TicketState.PUBLISHED).terminal(TicketState.CLOSED)
                .allowsCycle(TicketState.READY)
                .statePolicy(TicketState.READY, "publish the ticket")
                .statePolicy(TicketState.RETRY_PENDING, "retry the publication explicitly")
                .statePolicy(TicketState.REJECTED_BY_PROVIDER, "close the rejected ticket")
                .statePolicy(TicketState.INTEGRATION_FAILED, "close after manual review")
                .transition(TicketChange.PUBLISH, TicketState.READY, TicketState.PUBLISHED)
                        .action("publish", unavailable)
                        .recover(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY)
                        .endTransition()
                .transition(TicketChange.SCHEDULE_RETRY, TicketState.READY, TicketState.RETRY_PENDING).endTransition()
                .transition(TicketChange.RECORD_REJECTION, TicketState.READY, TicketState.REJECTED_BY_PROVIDER)
                        .endTransition()
                .transition(TicketChange.RETRY, TicketState.RETRY_PENDING, TicketState.READY)
                        .action("retry", unavailable)
                        .recover(PublicationFailures.Unavailable.class, TicketChange.MARK_INTEGRATION_FAILED)
                        .endTransition()
                .transition(TicketChange.MARK_INTEGRATION_FAILED, TicketState.RETRY_PENDING,
                        TicketState.INTEGRATION_FAILED).endTransition()
                .transition(TicketChange.CLOSE, TicketState.REJECTED_BY_PROVIDER, TicketState.CLOSED).endTransition()
                .transition(TicketChange.CLOSE, TicketState.INTEGRATION_FAILED, TicketState.CLOSED).endTransition()
                .build();
        StateMachine<Ticket, TicketState, TicketChange> first = RecoveryFixtures.machine(twoEdges);
        StateMachine<Ticket, TicketState, TicketChange> second = RecoveryFixtures.machine(publishing(unavailable,
                (TransitionBuilder<Ticket, TicketState, TicketChange> publish) ->
                        publish.recover(PublicationFailures.Unavailable.class, TicketChange.MARK_INTEGRATION_FAILED)));
        Ticket retrying = new Ticket("T-2");
        retrying.state = TicketState.RETRY_PENDING;
        List<String> ends = List.of(
                "first machine, " + routed(first, new Ticket("T-1"), TicketChange.PUBLISH),
                "first machine, " + routed(first, retrying, TicketChange.RETRY),
                "second machine, " + routed(second, new Ticket("T-3"), TicketChange.PUBLISH));
        assertEquals(List.of(
                "first machine, PUBLISH from READY: SCHEDULE_RETRY, Unavailable, RETRY_PENDING",
                "first machine, RETRY from RETRY_PENDING: MARK_INTEGRATION_FAILED, Unavailable, INTEGRATION_FAILED",
                "second machine, PUBLISH from READY: MARK_INTEGRATION_FAILED, Unavailable, INTEGRATION_FAILED"),
                ends);
    }

    /** R4, fourth row: a kind no rule declares falls back, and the outcome still reports it. */
    @Test
    void aKindNoRuleDeclaresTakesTheFallbackAndKeepsItsKind() {
        Ticket ticket = new Ticket("T-1");
        Outcome.Failed<Ticket, TicketState, TicketChange> failed = publishFails(RecoveryFixtures.machine(
                RecoveryFixtures.exampleGraph(returning(ActionOutcome.failed(Postponed.class, "not today")))), ticket);
        assertEquals(TicketChange.MARK_INTEGRATION_FAILED, failed.failureTransition());
        assertEquals(TicketState.INTEGRATION_FAILED, failed.movedTo());
        assertEquals(Optional.of(Postponed.class), failed.failureKind());
        assertEquals(TicketState.INTEGRATION_FAILED, ticket.state);
    }

    /** R4, fifth row: a kind no rule declares and no fallback; the exception carries no kind (D2). */
    @Test
    void aKindNoRuleDeclaresWithoutAFallbackIsUnhandled() {
        GraphDefinition<Ticket, TicketState, TicketChange> graph = publishing(
                returning(ActionOutcome.failed(Postponed.class, "not today")), ReturnedFailureRoutingTest::rulesOnly);
        Ticket ticket = new Ticket("T-1");
        UnhandledFailureException error = assertThrows(UnhandledFailureException.class,
                () -> RecoveryFixtures.machine(graph).execute(ticket, TicketChange.PUBLISH));
        assertEquals("not today", error.reason());
        assertNull(error.getCause());
        assertEquals(List.of("publish FAILED"), error.journal().stream()
                .map((ActionRecord record) -> record.action() + " " + record.status()).toList());
        assertEquals(TicketState.READY, ticket.state);
        assertEquals(0, ticket.stateWrites);
    }

    /**
     * A failure a rule routed is classified as any routed failure: FAILED, FAILURE_ROUTED, its returned reason as the
     * explanation, and the recovery edge as the only hop the call committed.
     */
    @Test
    void aFailureARuleRoutedIsClassifiedAsAnyRoutedFailure() {
        Ticket ticket = new Ticket("T-1");
        Outcome<Ticket, TicketState, TicketChange> outcome = RecoveryFixtures.machine(RecoveryFixtures.exampleGraph(
                returning(ActionOutcome.failed(PublicationFailures.Rejected.class, "content refused"))))
                .execute(ticket, TicketChange.PUBLISH);
        assertEquals(OutcomeStatus.FAILED, outcome.status());
        assertEquals(OutcomeCode.FAILURE_ROUTED, outcome.code());
        assertEquals(Optional.of("content refused"), outcome.explanation());
        assertEquals(TicketState.REJECTED_BY_PROVIDER, outcome.recordedState());
        assertEquals(List.of(TicketChange.RECORD_REJECTION),
                outcome.records().stream().map((TransitionRecord<?, ?, ?> hop) -> hop.transition()).toList());
        assertEquals(1, ticket.stateWrites);
    }

    /** The peer's Outcome.Failed reaches the parent action with the kind the peer's action returned. */
    @Test
    void aCascadedPeersFailedOutcomeCarriesItsFailureKindToTheParentAction() {
        Ticket parent = new Ticket("T-1");
        Ticket peer = new Ticket("T-2");
        AtomicReference<Outcome<Ticket, TicketState, TicketChange>> seen = new AtomicReference<>();
        GraphDefinition<Ticket, TicketState, TicketChange> graph = publishing(
                (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> {
                    if (ctx.entity() == peer) {
                        return ActionOutcome.failed(PublicationFailures.Rejected.class, "content refused");
                    }
                    seen.set(ctx.cascade(RecoveryFixtures.KEY, peer, TicketChange.PUBLISH));
                    return ActionOutcome.ok();
                },
                (TransitionBuilder<Ticket, TicketState, TicketChange> publish) ->
                        publish.recover(PublicationFailures.Rejected.class, TicketChange.RECORD_REJECTION));
        Outcome<Ticket, TicketState, TicketChange> outcome =
                RecoveryFixtures.machine(graph).execute(parent, TicketChange.PUBLISH);
        assertEquals(OutcomeStatus.APPLIED, outcome.status());
        Outcome.Failed<Ticket, TicketState, TicketChange> cascaded = seen.get().asFailed()
                .orElseThrow(() -> new AssertionError("expected the peer to fail: " + seen.get()));
        assertEquals(Optional.of(PublicationFailures.Rejected.class), cascaded.failureKind());
        assertEquals(TicketChange.RECORD_REJECTION, cascaded.failureTransition());
        assertEquals(TicketState.REJECTED_BY_PROVIDER, cascaded.movedTo());
        assertEquals(List.of(), cascaded.records());
        assertEquals(TicketState.PUBLISHED, parent.state);
        assertEquals(TicketState.REJECTED_BY_PROVIDER, peer.state);
    }

    /** Review Focus 3: a rule and the fallback may name one recovery; only failureKind tells which one routed. */
    @Test
    void aRuleAndTheFallbackOnOneRecoveryAreToldApartByTheFailureKind() {
        Ticket kinded = new Ticket("T-1");
        Ticket plain = new Ticket("T-2");
        StateMachine<Ticket, TicketState, TicketChange> machine = RecoveryFixtures.machine(publishing(
                (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> ctx.entity() == kinded
                        ? ActionOutcome.failed(PublicationFailures.Unavailable.class, "provider busy")
                        : ActionOutcome.failed("provider busy"),
                (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> publish
                        .recover(PublicationFailures.Unavailable.class, TicketChange.MARK_INTEGRATION_FAILED)
                        .onFailure(TicketChange.MARK_INTEGRATION_FAILED)));
        Outcome.Failed<Ticket, TicketState, TicketChange> byRule = publishFails(machine, kinded);
        Outcome.Failed<Ticket, TicketState, TicketChange> byFallback = publishFails(machine, plain);
        assertEquals(TicketChange.MARK_INTEGRATION_FAILED, byRule.failureTransition());
        assertEquals(TicketChange.MARK_INTEGRATION_FAILED, byFallback.failureTransition());
        assertEquals(TicketState.INTEGRATION_FAILED, byRule.movedTo());
        assertEquals(TicketState.INTEGRATION_FAILED, byFallback.movedTo());
        assertEquals(Optional.of(PublicationFailures.Unavailable.class), byRule.failureKind());
        assertEquals(Optional.empty(), byFallback.failureKind());
    }

    /**
     * Spec R7: a definition built without the DSL whose recovery names no edge from the origin aborts as an onFailure
     * that names none always did: the lookup of the recovery edge fails, and the call aborts in ACTION with that
     * failure as cause, nothing written and the action's journal entry FAILED.
     */
    @Test
    void aRecoveryThatNamesNoEdgeFromTheOriginAbortsAsAnOnFailureThatNamesNone() {
        Map<String, GraphDefinition<Ticket, TicketState, TicketChange>> routes = new LinkedHashMap<>();
        routes.put("onFailure", publishing(returning(ActionOutcome.failed("provider busy")),
                (TransitionBuilder<Ticket, TicketState, TicketChange> publish) ->
                        publish.onFailure(TicketChange.SCHEDULE_RETRY)));
        routes.put("recover", publishing(
                returning(ActionOutcome.failed(PublicationFailures.Unavailable.class, "provider busy")),
                (TransitionBuilder<Ticket, TicketState, TicketChange> publish) ->
                        publish.recover(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY)));
        List<String> ends = new ArrayList<>();
        for (Map.Entry<String, GraphDefinition<Ticket, TicketState, TicketChange>> route : routes.entrySet()) {
            StateMachine<Ticket, TicketState, TicketChange> machine = RecoveryFixtures.machine(
                    new WithoutEdge(route.getValue(), TicketState.READY, TicketChange.SCHEDULE_RETRY));
            Ticket ticket = new Ticket("T-1");
            TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                    () -> machine.execute(ticket, TicketChange.PUBLISH));
            ends.add(route.getKey() + ": " + error.getClass().getSimpleName() + " in " + error.phase()
                    + " caused by " + error.getCause().getClass().getSimpleName() + ", journal "
                    + error.journal().stream().map((ActionRecord record) -> record.action() + " " + record.status())
                            .toList()
                    + ", " + ticket.state + ", " + ticket.stateWrites + " writes");
        }
        assertEquals(List.of(
                "onFailure: TransitionAbortedException in ACTION caused by NoSuchElementException, journal"
                        + " [publish FAILED], READY, 0 writes",
                "recover: TransitionAbortedException in ACTION caused by NoSuchElementException, journal"
                        + " [publish FAILED], READY, 0 writes"), ends);
    }

    /** The delegate's graph without the edge keyed by {@code (from, id)}: a definition only a hand can build. */
    private static final class WithoutEdge implements GraphDefinition<Ticket, TicketState, TicketChange> {

        private final GraphDefinition<Ticket, TicketState, TicketChange> delegate;
        private final TransitionKey<TicketState, TicketChange> missing;

        private WithoutEdge(GraphDefinition<Ticket, TicketState, TicketChange> delegate, TicketState from,
                            TicketChange id) {
            this.delegate = delegate;
            this.missing = new TransitionKey<>(from, id);
        }

        private boolean kept(TransitionSpec<Ticket, TicketState, TicketChange> spec) {
            return !spec.key().equals(missing);
        }

        @Override
        public MachineKey<Ticket, TicketState, TicketChange> key() {
            return delegate.key();
        }

        @Override
        public TicketState initial() {
            return delegate.initial();
        }

        @Override
        public Set<TicketState> terminals() {
            return delegate.terminals();
        }

        @Override
        public DirectedGraph<TicketState, TicketChange> graph() {
            return delegate.graph();
        }

        @Override
        public List<TransitionSpec<Ticket, TicketState, TicketChange>> transitions() {
            return delegate.transitions().stream().filter(this::kept).toList();
        }

        @Override
        public List<TransitionSpec<Ticket, TicketState, TicketChange>> outgoingFrom(TicketState state) {
            return delegate.outgoingFrom(state).stream().filter(this::kept).toList();
        }

        @Override
        public Optional<TransitionSpec<Ticket, TicketState, TicketChange>> findTransition(TicketState from,
                                                                                         TicketChange transition) {
            return delegate.findTransition(from, transition).filter(this::kept);
        }

        @Override
        public StatePolicy<TicketState, TicketChange> policy(TicketState state) {
            return delegate.policy(state);
        }

        @Override
        public Optional<WatchSpec<Ticket, TicketState, TicketChange, ?, ?, ?>> watch(TicketState state) {
            return delegate.watch(state);
        }

        @Override
        public boolean hasAsync() {
            return delegate.hasAsync();
        }
    }
}
