package io.github.graphmaquina.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.ActionRecord;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.StatePolicy;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.api.WatchSpec;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.error.AfterCommitException;
import io.github.graphmaquina.error.CommitFailedException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.error.UnhandledFailureException;
import io.github.graphmaquina.fixtures.RecoveryFixtures;
import io.github.graphmaquina.fixtures.RecoveryFixtures.InvalidResponseException;
import io.github.graphmaquina.fixtures.RecoveryFixtures.ProviderRejectedException;
import io.github.graphmaquina.fixtures.RecoveryFixtures.PublicationFailures;
import io.github.graphmaquina.fixtures.RecoveryFixtures.ServiceUnavailableException;
import io.github.graphmaquina.fixtures.RecoveryFixtures.Ticket;
import io.github.graphmaquina.fixtures.RecoveryFixtures.TicketChange;
import io.github.graphmaquina.fixtures.RecoveryFixtures.TicketState;
import io.github.graphmaquina.fixtures.Sneaky;
import io.github.graphmaquina.graph.DirectedGraph;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * A throwable the action throws takes the route of the most specific type its edge declares that accepts it,
 * among the types of its recovery rules and of its own failsOn, after the ownership, exclusion and poison checks; one
 * that no declared type accepts aborts, whatever fallback the edge has. The cause keeps its identity and the reason
 * its composition. Guards, setters and hooks are never classified.
 */
class ThrownFailureRoutingTest {

    /** Admitted by a rule; reading its message fails, and every read is counted. */
    private static final class UnreadableFailure extends RuntimeException {
        private int reads;

        @Override
        public String getMessage() {
            reads++;
            throw new IllegalStateException("the message of this failure cannot be read");
        }
    }

    /** The publication graph with {@code action} on PUBLISH, under the rules and fallback {@code rules} declares. */
    private static GraphDefinition<Ticket, TicketState, TicketChange> publishing(
            TransitionAction<Ticket, TicketState, TicketChange> action,
            UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>> rules) {
        return RecoveryFixtures.graph((TransitionBuilder<Ticket, TicketState, TicketChange> publish) ->
                rules.apply(publish.action("publish", action)));
    }

    /** A PUBLISH action that throws {@code failure} every time, whatever its type, checked ones included. */
    private static TransitionAction<Ticket, TicketState, TicketChange> throwing(Throwable failure) {
        return (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> {
            throw Sneaky.raise(failure);
        };
    }

    /** Runs PUBLISH on {@code ticket} and narrows the outcome to Failed, failing the test with it otherwise. */
    private static Outcome.Failed<Ticket, TicketState, TicketChange> publishFails(
            StateMachine<Ticket, TicketState, TicketChange> machine, Ticket ticket) {
        Outcome<Ticket, TicketState, TicketChange> outcome = machine.execute(ticket, TicketChange.PUBLISH);
        return outcome.asFailed().orElseThrow(() -> new AssertionError("expected a failed outcome: " + outcome));
    }

    /** Where PUBLISH took {@code ticket} after {@code failure}: route, kind and cause, or the abort and its cause. */
    private static String end(StateMachine<Ticket, TicketState, TicketChange> machine, Ticket ticket,
                              Throwable failure) {
        String end;
        try {
            Outcome.Failed<Ticket, TicketState, TicketChange> failed = publishFails(machine, ticket);
            end = failed.failureTransition() + ", kind "
                    + failed.failureKind().map(Class::getSimpleName).orElse("none") + ", cause "
                    + (failed.cause().orElseThrow() == failure ? "the exception" : failed.cause());
        } catch (TransitionAbortedException aborted) {
            end = "aborted by " + (aborted.getCause() == failure ? "the exception" : aborted.getCause());
        }
        return end + ", " + ticket.state;
    }

    /** Each declared exception of the example graph takes its own route, and any other one aborts. */
    @Test
    void theExampleRoutesEachDeclaredExceptionAndAbortsOnAnyOther() {
        Map<String, Throwable> failures = new LinkedHashMap<>();
        failures.put("unavailable", new ServiceUnavailableException("provider down"));
        failures.put("rejected", new ProviderRejectedException("content refused"));
        failures.put("invalid", new InvalidResponseException("garbled body"));
        failures.put("undeclared", new IllegalStateException("adapter bug"));
        List<String> ends = new ArrayList<>();
        for (Map.Entry<String, Throwable> failure : failures.entrySet()) {
            StateMachine<Ticket, TicketState, TicketChange> machine =
                    RecoveryFixtures.machine(RecoveryFixtures.exampleGraph(throwing(failure.getValue())));
            ends.add(failure.getKey() + ": " + end(machine, new Ticket(failure.getKey()), failure.getValue()));
        }
        assertEquals(List.of(
                "unavailable: SCHEDULE_RETRY, kind Unavailable, cause the exception, RETRY_PENDING",
                "rejected: RECORD_REJECTION, kind Rejected, cause the exception, REJECTED_BY_PROVIDER",
                "invalid: MARK_INTEGRATION_FAILED, kind none, cause the exception, INTEGRATION_FAILED",
                "undeclared: aborted by the exception, READY"), ends);
    }

    @Test
    void aRuleAdmitsTheTypesItListsWithoutAnyFailsOn() {
        ServiceUnavailableException down = new ServiceUnavailableException("provider down");
        GraphDefinition<Ticket, TicketState, TicketChange> graph = publishing(throwing(down),
                (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> publish.recover(
                        PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY,
                        ServiceUnavailableException.class));
        Ticket ticket = new Ticket("T-1");
        Outcome.Failed<Ticket, TicketState, TicketChange> failed =
                publishFails(RecoveryFixtures.machine(graph), ticket);
        assertEquals(TicketChange.SCHEDULE_RETRY, failed.failureTransition());
        assertEquals(TicketState.RETRY_PENDING, failed.movedTo());
        assertEquals(Optional.of(PublicationFailures.Unavailable.class), failed.failureKind());
        assertSame(down, failed.cause().orElseThrow());
        assertEquals("ServiceUnavailableException: provider down", failed.reason());
        assertEquals(Optional.empty(), failed.explanation());
        assertEquals(TicketState.RETRY_PENDING, ticket.state);
    }

    /** A checked exception a synchronous action throws without declaring it is routed as an unchecked one is. */
    @Test
    void aCheckedExceptionThrownByASynchronousActionIsRoutedByARule() {
        IOException upload = new IOException("upload failed");
        GraphDefinition<Ticket, TicketState, TicketChange> graph = publishing(throwing(upload),
                (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> publish.recover(
                        PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY, IOException.class));
        Ticket ticket = new Ticket("T-1");
        Outcome.Failed<Ticket, TicketState, TicketChange> failed =
                publishFails(RecoveryFixtures.machine(graph), ticket);
        assertEquals(TicketChange.SCHEDULE_RETRY, failed.failureTransition());
        assertEquals(Optional.of(PublicationFailures.Unavailable.class), failed.failureKind());
        assertSame(upload, failed.cause().orElseThrow());
        assertEquals("IOException: upload failed", failed.reason());
    }

    @Test
    void betweenRulesTheSubclassWinsInEitherDeclarationOrder() {
        List<String> ends = new ArrayList<>();
        for (boolean subclassFirst : List.of(false, true)) {
            for (IOException failure : List.of(new SocketTimeoutException("read timed out"),
                    new IOException("connection reset"))) {
                StateMachine<Ticket, TicketState, TicketChange> machine =
                        RecoveryFixtures.machine(publishing(throwing(failure), twoRules(subclassFirst)));
                ends.add((subclassFirst ? "subclass first, " : "subclass last, ")
                        + failure.getClass().getSimpleName() + ": " + end(machine, new Ticket("T-1"), failure));
            }
        }
        assertEquals(List.of(
                "subclass last, SocketTimeoutException: RECORD_REJECTION, kind Rejected, cause the exception,"
                        + " REJECTED_BY_PROVIDER",
                "subclass last, IOException: SCHEDULE_RETRY, kind Unavailable, cause the exception, RETRY_PENDING",
                "subclass first, SocketTimeoutException: RECORD_REJECTION, kind Rejected, cause the exception,"
                        + " REJECTED_BY_PROVIDER",
                "subclass first, IOException: SCHEDULE_RETRY, kind Unavailable, cause the exception, RETRY_PENDING"),
                ends);
    }

    /** IOException routes to SCHEDULE_RETRY, SocketTimeoutException to RECORD_REJECTION, declared in either order. */
    private static UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>> twoRules(boolean subclassFirst) {
        if (subclassFirst) {
            return (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> publish
                    .recover(PublicationFailures.Rejected.class, TicketChange.RECORD_REJECTION,
                            SocketTimeoutException.class)
                    .recover(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY, IOException.class);
        }
        return (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> publish
                .recover(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY, IOException.class)
                .recover(PublicationFailures.Rejected.class, TicketChange.RECORD_REJECTION,
                        SocketTimeoutException.class);
    }

    /**
     * With the subtype in failsOn and the supertype in a rule, and the other way round, the more specific declaration
     * wins wherever it sits.
     */
    @Test
    void theMostSpecificTypeWinsBetweenARuleAndFailsOn() {
        UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>> legacySubtype =
                (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> publish
                        .failsOn(SocketTimeoutException.class).onFailure(TicketChange.MARK_INTEGRATION_FAILED)
                        .recover(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY, IOException.class);
        UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>> ruleSubtype =
                (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> publish
                        .failsOn(IOException.class).onFailure(TicketChange.MARK_INTEGRATION_FAILED)
                        .recover(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY,
                                SocketTimeoutException.class);
        Map<String, UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>>> edges =
                new LinkedHashMap<>();
        edges.put("failsOn SocketTimeoutException, rule IOException", legacySubtype);
        edges.put("failsOn IOException, rule SocketTimeoutException", ruleSubtype);
        List<String> ends = new ArrayList<>();
        for (Map.Entry<String, UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>>> edge
                : edges.entrySet()) {
            for (IOException failure : List.of(new SocketTimeoutException("read timed out"),
                    new IOException("connection reset"))) {
                StateMachine<Ticket, TicketState, TicketChange> machine =
                        RecoveryFixtures.machine(publishing(throwing(failure), edge.getValue()));
                ends.add(edge.getKey() + ", " + failure.getClass().getSimpleName() + ": "
                        + end(machine, new Ticket("T-1"), failure));
            }
        }
        assertEquals(List.of(
                "failsOn SocketTimeoutException, rule IOException, SocketTimeoutException: MARK_INTEGRATION_FAILED,"
                        + " kind none, cause the exception, INTEGRATION_FAILED",
                "failsOn SocketTimeoutException, rule IOException, IOException: SCHEDULE_RETRY, kind Unavailable,"
                        + " cause the exception, RETRY_PENDING",
                "failsOn IOException, rule SocketTimeoutException, SocketTimeoutException: SCHEDULE_RETRY,"
                        + " kind Unavailable, cause the exception, RETRY_PENDING",
                "failsOn IOException, rule SocketTimeoutException, IOException: MARK_INTEGRATION_FAILED, kind none,"
                        + " cause the exception, INTEGRATION_FAILED"), ends);
    }

    /** A fallback never admits anything: an exception no declared type accepts aborts with a THREW journal. */
    @Test
    void anExceptionNoDeclaredTypeAcceptsAbortsEvenWithAFallback() {
        IllegalStateException bug = new IllegalStateException("adapter bug");
        Ticket ticket = new Ticket("T-1");
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                () -> RecoveryFixtures.machine(RecoveryFixtures.exampleGraph(throwing(bug)))
                        .execute(ticket, TicketChange.PUBLISH));
        assertEquals(CallPhase.ACTION, error.phase());
        assertSame(bug, error.getCause());
        assertEquals(List.of("publish THREW"), error.journal().stream()
                .map((ActionRecord record) -> record.action() + " " + record.status()).toList());
        assertEquals(TicketState.READY, ticket.state);
        assertEquals(0, ticket.stateWrites);
    }

    /** A failsOn type that wins needs a fallback; a rule's type does not. */
    @Test
    void aFailsOnTypeThatWinsWithoutAFallbackIsUnhandledWhileARuleNeedsNone() {
        UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>> rules =
                (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> publish
                        .recover(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY,
                                ServiceUnavailableException.class)
                        .failsOn(InvalidResponseException.class);
        InvalidResponseException garbled = new InvalidResponseException("garbled body");
        Ticket unhandled = new Ticket("T-1");
        UnhandledFailureException error = assertThrows(UnhandledFailureException.class,
                () -> RecoveryFixtures.machine(publishing(throwing(garbled), rules))
                        .execute(unhandled, TicketChange.PUBLISH));
        assertEquals("InvalidResponseException: garbled body", error.reason());
        assertSame(garbled, error.getCause());
        assertEquals(TicketState.READY, unhandled.state);

        ServiceUnavailableException down = new ServiceUnavailableException("provider down");
        Ticket routed = new Ticket("T-2");
        Outcome.Failed<Ticket, TicketState, TicketChange> failed =
                publishFails(RecoveryFixtures.machine(publishing(throwing(down), rules)), routed);
        assertEquals(TicketChange.SCHEDULE_RETRY, failed.failureTransition());
        assertSame(down, failed.cause().orElseThrow());
    }

    /** The reason is composed as always: the simple name and the message, or the binary name of an anonymous class. */
    @Test
    void theReasonOfARuleRoutedExceptionIsComposedAsAlways() {
        IllegalStateException anonymous = new IllegalStateException() { };
        ServiceUnavailableException silent = new ServiceUnavailableException(null);
        UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>> rules =
                (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> publish
                        .recover(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY,
                                List.of(IllegalStateException.class, ServiceUnavailableException.class));
        Outcome.Failed<Ticket, TicketState, TicketChange> unnamed = publishFails(
                RecoveryFixtures.machine(publishing(throwing(anonymous), rules)), new Ticket("T-1"));
        assertEquals(anonymous.getClass().getName(), unnamed.reason());
        assertSame(anonymous, unnamed.cause().orElseThrow());
        Outcome.Failed<Ticket, TicketState, TicketChange> messageless = publishFails(
                RecoveryFixtures.machine(publishing(throwing(silent), rules)), new Ticket("T-2"));
        assertEquals("ServiceUnavailableException", messageless.reason());
    }

    /**
     * Exclusion comes before the declarations: a CancellationException is an IllegalStateException, yet neither a
     * rule nor failsOn on IllegalStateException routes one thrown synchronously.
     */
    @Test
    void aCancellationExceptionIsNeverRoutedByATypeThatAcceptsIt() {
        Map<String, UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>>> edges =
                new LinkedHashMap<>();
        edges.put("rule", (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> publish
                .recover(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY,
                        IllegalStateException.class)
                .onFailure(TicketChange.MARK_INTEGRATION_FAILED));
        edges.put("failsOn", (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> publish
                .failsOn(IllegalStateException.class).onFailure(TicketChange.MARK_INTEGRATION_FAILED));
        List<String> ends = new ArrayList<>();
        for (Map.Entry<String, UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>>> edge
                : edges.entrySet()) {
            CancellationException cancelled = new CancellationException("cancelled by the provider");
            StateMachine<Ticket, TicketState, TicketChange> machine =
                    RecoveryFixtures.machine(publishing(throwing(cancelled), edge.getValue()));
            ends.add(edge.getKey() + ": " + end(machine, new Ticket("T-1"), cancelled));
        }
        assertEquals(List.of("rule: aborted by the exception, READY", "failsOn: aborted by the exception, READY"),
                ends);
    }

    /** An Error the action throws propagates as itself, whatever the edge declares. */
    @Test
    void anErrorThrownByTheActionPropagatesUnchanged() {
        AssertionError broken = new AssertionError("invariant broken");
        Ticket ticket = new Ticket("T-1");
        StateMachine<Ticket, TicketState, TicketChange> machine =
                RecoveryFixtures.machine(RecoveryFixtures.exampleGraph(throwing(broken)));
        assertSame(broken, assertThrows(AssertionError.class, () -> machine.execute(ticket, TicketChange.PUBLISH)));
        assertEquals(TicketState.READY, ticket.state);
    }

    /**
     * A failure of another call is a library exception, so no declared type routes it, not even RuntimeException in
     * the failsOn of a definition built by hand: it becomes the cause of this call's abort. Both calls run on
     * test-owned workers under independent bounded waits, with futures cancelled and workers stopped in finally.
     */
    @Test
    void aFailureOfAnotherCallIsNeverRouted() throws Exception {
        ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        AtomicReference<CompletableFuture<Outcome<Ticket, TicketState, TicketChange>>> foreignCall =
                new AtomicReference<>();
        AtomicReference<Throwable> foreignFailure = new AtomicReference<>();
        Future<TransitionAbortedException> outerCall = null;
        try {
            StateMachine<Ticket, TicketState, TicketChange> other = StateMachines.from(
                            publishing(throwing(new IllegalStateException("the other call failed")),
                                    UnaryOperator.identity()))
                    .state((Ticket ticket) -> ticket.state, (Ticket ticket, TicketState state) -> {
                        ticket.state = state;
                        ticket.stateWrites++;
                    })
                    .identity((Ticket ticket) -> ticket.id)
                    .asyncExecutor(workers)
                    .build();
            Ticket elsewhere = new Ticket("T-2");
            GraphDefinition<Ticket, TicketState, TicketChange> listing = new ListingRuntimeException(publishing(
                    (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> {
                        CompletableFuture<Outcome<Ticket, TicketState, TicketChange>> pending =
                                other.async().execute(elsewhere, TicketChange.PUBLISH);
                        foreignCall.set(pending);
                        try {
                            pending.get(5, TimeUnit.SECONDS);
                        } catch (ExecutionException transport) {
                            Throwable foreign = transport.getCause();
                            foreignFailure.set(foreign);
                            throw Sneaky.raise(foreign);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw Sneaky.raise(interrupted);
                        } catch (TimeoutException timeout) {
                            throw Sneaky.raise(timeout);
                        }
                        return ActionOutcome.ok();
                    },
                    ThrownFailureRoutingTest::ruleOnIllegalState));
            Ticket ticket = new Ticket("T-1");
            outerCall = workers.submit(() -> assertThrows(TransitionAbortedException.class,
                    () -> RecoveryFixtures.machine(listing).execute(ticket, TicketChange.PUBLISH)));
            TransitionAbortedException error = outerCall.get(10, TimeUnit.SECONDS);
            TransitionAbortedException foreign = assertInstanceOf(TransitionAbortedException.class, error.getCause());
            assertSame(foreignFailure.get(), foreign);
            assertSame(ticket, error.call().entity());
            assertSame(elsewhere, foreign.call().entity());
            assertNotSame(error.call(), foreign.call());
            assertEquals(CallPhase.ACTION, error.phase());
            assertEquals(TicketState.READY, ticket.state);
            assertEquals(TicketState.READY, elsewhere.state);
        } finally {
            CompletableFuture<Outcome<Ticket, TicketState, TicketChange>> foreign = foreignCall.get();
            if (foreign != null) {
                foreign.cancel(true);
            }
            if (outerCall != null) {
                outerCall.cancel(true);
            }
            workers.shutdownNow();
        }
    }

    /**
     * A failure of this very call that the action lets through ends the call as itself, before any declared type is
     * consulted, even RuntimeException in the failsOn of a definition built by hand.
     */
    @Test
    void aFailureOfThisCallThatTheActionLetsThroughEndsTheCallAsItIs() {
        Ticket peer = new Ticket("T-2");
        AtomicReference<RuntimeException> seen = new AtomicReference<>();
        GraphDefinition<Ticket, TicketState, TicketChange> listing = new ListingRuntimeException(publishing(
                (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> {
                    if (ctx.entity() == peer) {
                        throw Sneaky.raise(new IOException("ledger down"));
                    }
                    try {
                        ctx.cascade(RecoveryFixtures.KEY, peer, TicketChange.PUBLISH);
                    } catch (RuntimeException failure) {
                        seen.set(failure);
                        throw failure;
                    }
                    return ActionOutcome.ok();
                },
                ThrownFailureRoutingTest::ruleOnIllegalState));
        Ticket ticket = new Ticket("T-1");
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                () -> RecoveryFixtures.machine(listing).execute(ticket, TicketChange.PUBLISH));
        assertSame(seen.get(), error);
        assertInstanceOf(IOException.class, error.getCause());
        assertEquals(TicketState.READY, ticket.state);
        assertEquals(TicketState.READY, peer.state);
        assertEquals(0, ticket.stateWrites + peer.stateWrites);
    }

    /**
     * After a caught cascade failure poisoned the transaction, an exception a rule admits ends the call with the
     * poison. The poison is thrown before a route is chosen and before the reason is composed, so the exception's
     * message is never read.
     */
    @Test
    void withAPoisonedTransactionThePoisonWinsAndTheMessageIsNeverRead() {
        Ticket peer = new Ticket("T-2");
        UnreadableFailure unreadable = new UnreadableFailure();
        AtomicReference<RuntimeException> poison = new AtomicReference<>();
        GraphDefinition<Ticket, TicketState, TicketChange> graph = publishing(
                (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> {
                    if (ctx.entity() == peer) {
                        throw Sneaky.raise(new IOException("ledger down"));
                    }
                    try {
                        ctx.cascade(RecoveryFixtures.KEY, peer, TicketChange.PUBLISH);
                    } catch (TransitionAbortedException failure) {
                        poison.set(failure);
                        throw unreadable;
                    }
                    return ActionOutcome.ok();
                },
                (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> publish
                        .recover(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY,
                                UnreadableFailure.class)
                        .onFailure(TicketChange.MARK_INTEGRATION_FAILED));
        Ticket ticket = new Ticket("T-1");
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                () -> RecoveryFixtures.machine(graph).execute(ticket, TicketChange.PUBLISH));
        assertSame(poison.get(), error);
        assertEquals(0, unreadable.reads);
        assertEquals(TicketState.READY, ticket.state);
        assertEquals(TicketState.READY, peer.state);
        assertEquals(0, ticket.stateWrites + peer.stateWrites);
    }

    /**
     * Only the action's failures are classified: a guard, a setter or a hook that throws a type a rule declares ends
     * the call as it always did, as a guard abort, a commit failure or a hook failure, never through a route.
     */
    @Test
    void guardSetterAndHookExceptionsAreNeverClassifiedEvenWhenARuleListsTheirType() {
        ServiceUnavailableException down = new ServiceUnavailableException("provider down");
        TransitionAction<Ticket, TicketState, TicketChange> publishes =
                (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> ActionOutcome.ok();
        UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>> rules =
                (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> publish
                        .recover(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY,
                                ServiceUnavailableException.class)
                        .onFailure(TicketChange.MARK_INTEGRATION_FAILED);

        GraphDefinition<Ticket, TicketState, TicketChange> guarded = RecoveryFixtures.graph(
                (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> rules.apply(publish
                        .action("publish", publishes)
                        .guard("reachable", (GuardContext<Ticket, TicketState, TicketChange> ctx) -> {
                            throw down;
                        })));
        Ticket blocked = new Ticket("T-1");
        TransitionAbortedException guardFailure = assertThrows(TransitionAbortedException.class,
                () -> RecoveryFixtures.machine(guarded).execute(blocked, TicketChange.PUBLISH));
        assertEquals(CallPhase.GUARD, guardFailure.phase());
        assertSame(down, guardFailure.getCause());
        assertEquals(TicketState.READY, blocked.state);

        StateMachine<Ticket, TicketState, TicketChange> unwritable = StateMachines.from(publishing(publishes, rules))
                .state((Ticket ticket) -> ticket.state, (Ticket ticket, TicketState state) -> {
                    throw down;
                })
                .identity((Ticket ticket) -> ticket.id)
                .build();
        Ticket unsaved = new Ticket("T-2");
        CommitFailedException commitFailure = assertThrows(CommitFailedException.class,
                () -> unwritable.execute(unsaved, TicketChange.PUBLISH));
        assertSame(down, commitFailure.getCause());
        assertEquals(TicketState.READY, unsaved.state);

        GraphDefinition<Ticket, TicketState, TicketChange> notifying = RecoveryFixtures.graph(
                (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> rules.apply(publish
                        .action("publish", publishes)
                        .afterCommit("notify", (Ticket ticket) -> {
                            throw down;
                        })));
        Ticket notified = new Ticket("T-3");
        AfterCommitException hookFailure = assertThrows(AfterCommitException.class,
                () -> RecoveryFixtures.machine(notifying).execute(notified, TicketChange.PUBLISH));
        assertSame(down, hookFailure.getCause());
        assertEquals(TicketState.PUBLISHED, notified.state);
    }

    /** A rule on IllegalStateException and the fallback, for the definitions built by hand above. */
    private static TransitionBuilder<Ticket, TicketState, TicketChange> ruleOnIllegalState(
            TransitionBuilder<Ticket, TicketState, TicketChange> publish) {
        return publish
                .recover(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY,
                        IllegalStateException.class)
                .onFailure(TicketChange.MARK_INTEGRATION_FAILED);
    }

    /**
     * The delegate's graph with RuntimeException in PUBLISH's own failsOn, beside its rules: the DSL refuses that type
     * (SG302), and only a definition built by hand can carry it.
     */
    private static final class ListingRuntimeException
            implements GraphDefinition<Ticket, TicketState, TicketChange> {

        private final GraphDefinition<Ticket, TicketState, TicketChange> delegate;

        private ListingRuntimeException(GraphDefinition<Ticket, TicketState, TicketChange> delegate) {
            this.delegate = delegate;
        }

        private TransitionSpec<Ticket, TicketState, TicketChange> rebuilt(
                TransitionSpec<Ticket, TicketState, TicketChange> spec) {
            if (spec.transition() != TicketChange.PUBLISH) {
                return spec;
            }
            return new TransitionSpec<>(spec.from(), spec.transition(), spec.to(), spec.action(), spec.guards(),
                    spec.onFailure(), List.of(RuntimeException.class), spec.timeout(), spec.afterCommit(),
                    spec.recoveryRules());
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
            return delegate.transitions().stream().map(this::rebuilt).toList();
        }

        @Override
        public List<TransitionSpec<Ticket, TicketState, TicketChange>> outgoingFrom(TicketState state) {
            return delegate.outgoingFrom(state).stream().map(this::rebuilt).toList();
        }

        @Override
        public Optional<TransitionSpec<Ticket, TicketState, TicketChange>> findTransition(TicketState from,
                                                                                         TicketChange transition) {
            return delegate.findTransition(from, transition).map(this::rebuilt);
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
