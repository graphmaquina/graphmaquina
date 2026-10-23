package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.RecoveryFixtures.hops;
import static io.github.graphmaquina.fixtures.RecoveryFixtures.unavailable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.CommitContext;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.OutcomeCode;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.fixtures.RecoveryFixtures;
import io.github.graphmaquina.fixtures.RecoveryFixtures.ProviderRejectedException;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * The recovery on the asynchronous paths, once a route is selected (see {@link AsyncRecoveryTest}). A recovery waits
 * under the root's budget and under its own timeout. A recovery edge runs as declared: as a move or an action, without
 * its guards, with its own hook, and without chaining when it fails; a recovery that throws, or that the engine
 * aborts, keeps the exception that selected it as suppressed. Each test has an independent preemptive watchdog and
 * finally cleanup of pending stages, facade calls and test-owned workers.
 */
class AsyncRecoveryEdgeTest extends AsyncRecoveryHarness {

    /**
     * A recovery waits under the root's budget. Its stage stays pending, its own timeout is an hour, and the root may
     * wait 300 ms in all: only the root's deadline can end the call before the test stops waiting.
     */
    @Test
    void aRecoveryWaitsUnderTheRootBudget() throws Exception {
        bounded(() -> {
            assertTheRecoveryWaitEndsInATimeout(Duration.ofMillis(300), Duration.ofHours(1));
        });
    }

    /**
     * A recovery honours its own shorter timeout. Its stage stays pending, its own timeout is 200 ms, and the root may
     * wait an hour: only the recovery's own deadline can end the call before the test stops waiting.
     */
    @Test
    void aRecoveryHonoursItsOwnShorterTimeout() throws Exception {
        bounded(() -> {
            assertTheRecoveryWaitEndsInATimeout(Duration.ofHours(1), Duration.ofMillis(200));
        });
    }

    /**
     * PUBLISH returns the kind Unavailable, whose rule selects SCHEDULE_RETRY, an asynchronous recovery whose stage
     * stays pending under {@code recoveryTimeout}, in a machine whose roots wait {@code rootBudget} in all. One of the
     * two is far shorter than the test's own wait and the other far longer, so the call ends by the shorter one: an
     * abort in the recovery's wait, caused by a TimeoutException, with nothing written and the recovery's stage left
     * running. Completing that stage afterwards writes nothing either.
     */
    private void assertTheRecoveryWaitEndsInATimeout(Duration rootBudget, Duration recoveryTimeout)
            throws Exception {
        CompletableFuture<ActionOutcome> retry = pending();
        StateMachine<Ticket, TicketState, TicketChange> machine = machine(tickets(
                unavailable(),
                (TransitionBuilder<Ticket, TicketState, TicketChange> edge) -> edge
                        .asyncAction("schedule retry", (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> {
                            ctx.entity().calls.add("schedule retry");
                            return retry;
                        })
                        .timeout(recoveryTimeout)), rootBudget);
        Ticket ticket = new Ticket("t1");
        try {
            CompletableFuture<Outcome<Ticket, TicketState, TicketChange>> call =
                    start(machine, ticket);
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> call.get(WAIT_SECONDS, TimeUnit.SECONDS));
            TransitionAbortedException error = assertInstanceOf(TransitionAbortedException.class, failure.getCause());
            assertInstanceOf(TimeoutException.class, error.getCause());
            assertEquals(Optional.of(TicketChange.SCHEDULE_RETRY), error.transition());
            assertEquals(Optional.of("schedule retry"), error.action());
            assertEquals(List.of("publish FAILED", "schedule retry THREW"), journal(error));
            assertEquals(List.of("publish", "schedule retry"), ticket.calls);
            assertEquals(TicketState.READY, ticket.state);
            assertEquals(0, ticket.stateWrites);
            assertFalse(retry.isDone(), "the engine completed or cancelled the recovery's stage");
        } finally {
            retry.complete(ActionOutcome.ok());
        }
        assertEquals(TicketState.READY, ticket.state);
        assertEquals(0, ticket.stateWrites);
    }

    /**
     * A move recovers: the stage completes with the kind Rejected, and RECORD_REJECTION, a plain move in the fixture,
     * commits as the only hop. The failed PUBLISH leaves no hop behind.
     */
    @Test
    void aMoveRecoveryCommitsOnlyItsOwnHop() throws Exception {
        bounded(() -> {
            StateMachine<Ticket, TicketState, TicketChange> machine = machine(
                    RecoveryFixtures.exampleAsyncGraph((TransitionContext<Ticket, TicketState, TicketChange> ctx) ->
                            CompletableFuture.completedFuture(
                                    ActionOutcome.failed(PublicationFailures.Rejected.class, "content rejected"))));
            Ticket ticket = new Ticket("t1");
            Outcome<Ticket, TicketState, TicketChange> outcome = viaFacade(machine, ticket);
            assertEquals("FAILURE_ROUTED RECORD_REJECTION to REJECTED_BY_PROVIDER, kind Rejected, no cause;"
                    + " ticket REJECTED_BY_PROVIDER after 1 write(s)", routed(ticket, null, outcome));
            assertEquals(List.of("READY -RECORD_REJECTION-> REJECTED_BY_PROVIDER MOVE no action at depth 0"),
                    hops(outcome));
        });
    }

    /**
     * An action recovers, synchronous or asynchronous: SCHEDULE_RETRY runs its own action, whose stage, when it has
     * one, the test completes while the recovery waits, and its hop is the only one the call commits.
     */
    @Test
    void synchronousAndAsynchronousRecoveryActionsCommitTheirHop() throws Exception {
        bounded(() -> {
            Ticket syncTicket = new Ticket("sync");
            Outcome<Ticket, TicketState, TicketChange> sync =
                    machine(tickets(unavailable())).execute(syncTicket, TicketChange.PUBLISH);
            RegisteredStage<ActionOutcome> retried = registeredStage();
            StateMachine<Ticket, TicketState, TicketChange> asyncMachine = machine(tickets(unavailable(),
                    (TransitionBuilder<Ticket, TicketState, TicketChange> edge) -> edge.asyncAction("schedule retry",
                            (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> {
                                ctx.entity().calls.add("schedule retry");
                                return retried;
                            })));
            Ticket asyncTicket = new Ticket("async");
            CompletableFuture<Outcome<Ticket, TicketState, TicketChange>> call =
                    start(asyncMachine, asyncTicket);
            retried.awaitPendingRegistration();
            retried.complete(ActionOutcome.ok());
            Outcome<Ticket, TicketState, TicketChange> async = call.get(WAIT_SECONDS, TimeUnit.SECONDS);
            String expected = "FAILURE_ROUTED SCHEDULE_RETRY to RETRY_PENDING, kind Unavailable, no cause;"
                    + " ticket RETRY_PENDING after 1 write(s)";
            List<String> hop = List.of("READY -SCHEDULE_RETRY-> RETRY_PENDING ACTION schedule retry at depth 0");
            assertEquals(expected, routed(syncTicket, null, sync));
            assertEquals(hop, hops(sync));
            assertEquals(List.of("publish", "schedule retry"), syncTicket.calls);
            assertEquals(expected, routed(asyncTicket, null, async));
            assertEquals(hop, hops(async));
            assertEquals(List.of("publish", "schedule retry"), asyncTicket.calls);
        });
    }

    /**
     * A recovery entry runs none of its edge's guards: SCHEDULE_RETRY carries a guard that always rejects, and yet it
     * recovers PUBLISH without that guard running. Asked for directly, the same edge runs the guard, which rejects.
     */
    @Test
    void aRecoveryEntrySkipsTheGuardsThatAnOrdinaryEntryRuns() {
        bounded(() -> {
            List<String> guarded = new ArrayList<>();
            StateMachine<Ticket, TicketState, TicketChange> machine = machine(tickets(
                    unavailable(),
                    (TransitionBuilder<Ticket, TicketState, TicketChange> edge) -> edge
                            .guard("retry budget", (GuardContext<Ticket, TicketState, TicketChange> ctx) -> {
                                guarded.add(ctx.entity().id);
                                return GuardResult.reject("no retry budget left");
                            })
                            .action("schedule retry", recording("schedule retry"))));
            Ticket recovering = new Ticket("recovering");
            assertEquals("FAILURE_ROUTED SCHEDULE_RETRY to RETRY_PENDING, kind Unavailable, no cause;"
                            + " ticket RETRY_PENDING after 1 write(s)",
                    routed(recovering, null, machine.execute(recovering, TicketChange.PUBLISH)));
            assertEquals(List.of(), guarded);
            Ticket asked = new Ticket("asked");
            Outcome<Ticket, TicketState, TicketChange> ordinary = machine.execute(asked, TicketChange.SCHEDULE_RETRY);
            assertEquals(OutcomeCode.GUARD_REJECTED, ordinary.code());
            assertEquals(Optional.of("no retry budget left"), ordinary.explanation());
            assertEquals(List.of("asked"), guarded);
            assertEquals(TicketState.READY, asked.state);
            assertEquals(0, asked.stateWrites);
        });
    }

    /**
     * An ordinary entry applies the edge's own rules, and a recovery entry does not chain. SCHEDULE_RETRY throws a
     * ProviderRejectedException, which its own rule routes to RECORD_REJECTION. Asked for directly, it takes that
     * route. Entered as PUBLISH's recovery, the same throw aborts the call with it as cause: neither its own rule, nor
     * its own fallback, nor PUBLISH's fallback runs, and nothing is written.
     */
    @Test
    void anOrdinaryEntryAppliesItsOwnRulesWhileARecoveryEntryDoesNotChain() {
        bounded(() -> {
            ProviderRejectedException refused = new ProviderRejectedException("the provider refuses retries");
            StateMachine<Ticket, TicketState, TicketChange> machine = machine(tickets(
                    unavailable(),
                    (TransitionBuilder<Ticket, TicketState, TicketChange> edge) -> edge
                            .action("schedule retry", (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> {
                                ctx.entity().calls.add("schedule retry");
                                throw refused;
                            })
                            .recover(PublicationFailures.Rejected.class, TicketChange.RECORD_REJECTION,
                                    ProviderRejectedException.class)
                            .onFailure(TicketChange.MARK_INTEGRATION_FAILED)));
            Ticket asked = new Ticket("asked");
            assertEquals("FAILURE_ROUTED RECORD_REJECTION to REJECTED_BY_PROVIDER, kind Rejected, the thrown exception;"
                            + " ticket REJECTED_BY_PROVIDER after 1 write(s)",
                    routed(asked, refused, machine.execute(asked, TicketChange.SCHEDULE_RETRY)));
            assertEquals(List.of("schedule retry", "record rejection"), asked.calls);
            Ticket recovering = new Ticket("recovering");
            TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                    () -> machine.execute(recovering, TicketChange.PUBLISH));
            assertSame(refused, error.getCause());
            assertEquals(List.of("publish FAILED", "schedule retry THREW"), journal(error));
            assertEquals(List.of("publish", "schedule retry"), recovering.calls);
            assertEquals(TicketState.READY, recovering.state);
            assertEquals(0, recovering.stateWrites);
        });
    }

    /**
     * PUBLISH with the rules of the example graph and a synchronous action that logs "publish" and throws
     * {@code down}, which the rule of the kind Unavailable routes to SCHEDULE_RETRY.
     */
    private static UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>> throwsUnavailable(
            ServiceUnavailableException down) {
        return (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> RecoveryFixtures.exampleRules(
                publish.action("publish", (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> {
                    ctx.entity().calls.add("publish");
                    throw down;
                }));
    }

    /** SCHEDULE_RETRY with a synchronous action that throws {@code failure}. */
    private static UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>> retryThrowing(
            RuntimeException failure) {
        return (TransitionBuilder<Ticket, TicketState, TicketChange> retry) -> retry.action("schedule retry",
                (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> {
                    throw failure;
                });
    }

    /**
     * A recovery that throws loses neither failure. PUBLISH throws a ServiceUnavailableException, which its rule
     * routes to SCHEDULE_RETRY, whose action then throws, or completes its stage with, another exception: the call
     * aborts with that one as cause and holds, by identity, the one that selected the route as suppressed. A recovery
     * that rethrows the very exception that selected it leaves that exception the cause alone, and a route that a
     * returned failure selected has no exception to keep.
     */
    @Test
    void aRecoveryThatThrowsKeepsTheExceptionThatSelectedItAsSuppressed() {
        bounded(() -> {
            ServiceUnavailableException down = new ServiceUnavailableException("provider down");
            IllegalStateException broken = new IllegalStateException("the scheduler is down");
            UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>> unavailableThrown =
                    throwsUnavailable(down);
            Map<String, UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>>> retries =
                    new LinkedHashMap<>();
            retries.put("sync", retryThrowing(broken));
            retries.put("async", (TransitionBuilder<Ticket, TicketState, TicketChange> retry) -> retry.asyncAction(
                    "schedule retry", (TransitionContext<Ticket, TicketState, TicketChange> ctx) ->
                            CompletableFuture.failedFuture(broken)));
            for (Map.Entry<String, UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>>> retry
                    : retries.entrySet()) {
                Ticket ticket = new Ticket(retry.getKey());
                StateMachine<Ticket, TicketState, TicketChange> machine =
                        machine(tickets(unavailableThrown, retry.getValue()));
                TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                        () -> machine.execute(ticket, TicketChange.PUBLISH), retry.getKey());
                assertSame(broken, error.getCause(), retry.getKey());
                assertEquals(1, error.getSuppressed().length, retry.getKey());
                assertSame(down, error.getSuppressed()[0], retry.getKey());
                assertEquals(TicketState.READY, ticket.state, retry.getKey());
            }

            StateMachine<Ticket, TicketState, TicketChange> rethrowing =
                    machine(tickets(unavailableThrown, retryThrowing(down)));
            TransitionAbortedException same = assertThrows(TransitionAbortedException.class,
                    () -> rethrowing.execute(new Ticket("rethrown"), TicketChange.PUBLISH));
            assertSame(down, same.getCause());
            assertEquals(0, same.getSuppressed().length);

            StateMachine<Ticket, TicketState, TicketChange> returning =
                    machine(tickets(unavailable(), retryThrowing(broken)));
            TransitionAbortedException noException = assertThrows(TransitionAbortedException.class,
                    () -> returning.execute(new Ticket("returned"), TicketChange.PUBLISH));
            assertSame(broken, noException.getCause());
            assertEquals(0, noException.getSuppressed().length);
        });
    }

    /**
     * The engine's own abort of a recovery step loses no failure either. PUBLISH throws a ServiceUnavailableException,
     * which its rule routes to SCHEDULE_RETRY, whose action then returns null or a null stage, or whose stage
     * completes with null or outlasts the recovery edge's timeout: the call ends in the engine's abort of that step,
     * which holds, by identity, the exception that selected the route as suppressed. When the recovery action has
     * poisoned the unit of work first, through a cascade it caught, the call ends in that first poison, which the
     * engine leaves as it is.
     */
    @Test
    void anAbortOfARecoveryStepByTheEngineKeepsTheExceptionThatSelectedItAsSuppressed() {
        bounded(() -> {
            ServiceUnavailableException down = new ServiceUnavailableException("provider down");
            Map<String, UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>>> retries =
                    new LinkedHashMap<>();
            retries.put("the action schedule retry returned null",
                    (TransitionBuilder<Ticket, TicketState, TicketChange> retry) -> retry.action("schedule retry",
                            (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> null));
            retries.put("the action schedule retry returned a null stage",
                    (TransitionBuilder<Ticket, TicketState, TicketChange> retry) -> retry.asyncAction(
                            "schedule retry", (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> null));
            retries.put("the action schedule retry returned null, from its stage",
                    (TransitionBuilder<Ticket, TicketState, TicketChange> retry) -> retry.asyncAction(
                            "schedule retry", (TransitionContext<Ticket, TicketState, TicketChange> ctx) ->
                                    CompletableFuture.<ActionOutcome>completedFuture(null)));
            retries.put("waiting for the action schedule retry timed out",
                    (TransitionBuilder<Ticket, TicketState, TicketChange> retry) -> retry.asyncAction(
                            "schedule retry", (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> pending())
                            .timeout(Duration.ofMillis(50)));
            for (Map.Entry<String, UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>>> retry
                    : retries.entrySet()) {
                String ending = retry.getKey().replace(", from its stage", "");
                Ticket ticket = new Ticket(retry.getKey());
                StateMachine<Ticket, TicketState, TicketChange> machine =
                        machine(tickets(throwsUnavailable(down), retry.getValue()));
                TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                        () -> machine.execute(ticket, TicketChange.PUBLISH), retry.getKey());
                assertTrue(error.getMessage().contains(": " + ending), error.getMessage());
                assertEquals(1, error.getSuppressed().length, retry.getKey());
                assertSame(down, error.getSuppressed()[0], retry.getKey());
                assertEquals(TicketState.READY, ticket.state, retry.getKey());
            }

            AtomicReference<TransitionAbortedException> caught = new AtomicReference<>();
            StateMachine<Ticket, TicketState, TicketChange> poisoning = machine(tickets(throwsUnavailable(down),
                    (TransitionBuilder<Ticket, TicketState, TicketChange> retry) -> retry.action("schedule retry",
                            (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> {
                                try {
                                    ctx.cascade(RecoveryFixtures.KEY, new Ticket("peer"), TicketChange.CLOSE);
                                } catch (TransitionAbortedException failure) {
                                    caught.set(failure);
                                }
                                return null;
                            })));
            TransitionAbortedException poison = assertThrows(TransitionAbortedException.class,
                    () -> poisoning.execute(new Ticket("poisoned"), TicketChange.PUBLISH));
            assertSame(caught.get(), poison);
            assertEquals(0, poison.getSuppressed().length);
        });
    }

    /**
     * The recovery edge's own hook runs once the recovery has committed and sees the committed state, while the hook
     * of the failed PUBLISH never runs. PUBLISH is asynchronous, and its stage fails while the facade's call waits.
     */
    @Test
    void theRecoveryEdgesOwnHookRunsAfterTheCommit() throws Exception {
        bounded(() -> {
            RegisteredStage<ActionOutcome> stage = registeredStage();
            StateMachine<Ticket, TicketState, TicketChange> machine = machine(tickets(
                    (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> RecoveryFixtures.exampleRules(
                            publish.asyncAction("publish",
                                    (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> {
                                        ctx.entity().calls.add("publish");
                                        return stage;
                                    }).afterCommit("published",
                                            (Ticket ticket) -> ticket.calls.add("hook of PUBLISH"))),
                    (TransitionBuilder<Ticket, TicketState, TicketChange> edge) -> edge
                            .action("schedule retry", recording("schedule retry"))
                            .afterCommitWith("retry scheduled",
                                    (Ticket ticket, CommitContext<TicketState, TicketChange> commit) ->
                                            ticket.calls.add("hook of " + commit.transition() + " sees " + ticket.state
                                                    + " after " + ticket.stateWrites + " write(s)"))));
            Ticket ticket = new Ticket("t1");
            CompletableFuture<Outcome<Ticket, TicketState, TicketChange>> call =
                    start(machine, ticket);
            stage.awaitPendingRegistration();
            stage.completeExceptionally(new ServiceUnavailableException("provider down"));
            assertEquals(OutcomeCode.FAILURE_ROUTED, call.get(WAIT_SECONDS, TimeUnit.SECONDS).code());
            assertEquals(List.of("publish", "schedule retry",
                    "hook of SCHEDULE_RETRY sees RETRY_PENDING after 1 write(s)"), ticket.calls);
        });
    }

    /**
     * SCHEDULE_RETRY with an action that fails in the three ways of a recovery: it returns a failure of the kind its
     * own rule lists, it throws {@code refused}, or its stage {@code unanswered} outlives its own timeout.
     */
    private static Map<String, UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>>> failingRetries(
            ProviderRejectedException refused, CompletableFuture<ActionOutcome> unanswered) {
        Map<String, UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>>> failures =
                new LinkedHashMap<>();
        failures.put("returns a failure", (TransitionBuilder<Ticket, TicketState, TicketChange> edge) -> edge
                .action("schedule retry", (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> {
                    ctx.entity().calls.add("schedule retry");
                    return ActionOutcome.failed(PublicationFailures.Rejected.class, "retry refused");
                }));
        failures.put("throws", (TransitionBuilder<Ticket, TicketState, TicketChange> edge) -> edge
                .action("schedule retry", (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> {
                    ctx.entity().calls.add("schedule retry");
                    throw refused;
                }));
        failures.put("times out", (TransitionBuilder<Ticket, TicketState, TicketChange> edge) -> edge
                .asyncAction("schedule retry", (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> {
                    ctx.entity().calls.add("schedule retry");
                    return unanswered;
                })
                .timeout(Duration.ofMillis(100)));
        return failures;
    }

    /**
     * A recovery that fails ends the call as the current contract says, and no other route is tried. SCHEDULE_RETRY
     * recovers PUBLISH and fails in three ways: it returns a failure of the kind its own rule lists, it throws the
     * type its own rule lists, or its stage outlives its own timeout. Each aborts the call: with no cause, since the
     * failure PUBLISH returned had none; with the thrown exception; with a TimeoutException. Neither its own rule,
     * nor its own fallback, nor PUBLISH's fallback runs, the journal keeps both actions, and nothing is written, not
     * even once the pending stage completes late.
     */
    @Test
    void aRecoveryThatFailsAbortsTheCallAndTriesNoOtherRoute() {
        bounded(() -> {
            ProviderRejectedException refused = new ProviderRejectedException("the provider refuses retries");
            CompletableFuture<ActionOutcome> unanswered = pending();
            Map<String, UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>>> failures =
                    failingRetries(refused, unanswered);
            List<String> ends = new ArrayList<>();
            List<Ticket> failedTickets = new ArrayList<>();
            try {
                for (Map.Entry<String, UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>>> failure
                        : failures.entrySet()) {
                    StateMachine<Ticket, TicketState, TicketChange> machine = machine(tickets(
                            unavailable(),
                            (TransitionBuilder<Ticket, TicketState, TicketChange> edge) ->
                                    failure.getValue().apply(edge)
                                            .recover(PublicationFailures.Rejected.class,
                                                    TicketChange.RECORD_REJECTION, ProviderRejectedException.class)
                                            .onFailure(TicketChange.MARK_INTEGRATION_FAILED)));
                    Ticket ticket = new Ticket(failure.getKey());
                    failedTickets.add(ticket);
                    TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                            () -> machine.execute(ticket, TicketChange.PUBLISH));
                    Throwable cause = error.getCause();
                    ends.add(failure.getKey() + ": " + error.getClass().getSimpleName() + " with "
                            + (cause == null ? "no cause" : cause == refused ? "the thrown exception"
                                    : cause.getClass().getSimpleName())
                            + ", journal " + journal(error) + ", calls " + ticket.calls + "; ticket " + ticket.state
                            + " after " + ticket.stateWrites + " write(s)");
                }
            } finally {
                unanswered.complete(ActionOutcome.ok());
            }
            assertEquals(List.of(
                    "returns a failure: TransitionAbortedException with no cause,"
                            + " journal [publish FAILED, schedule retry FAILED], calls [publish, schedule retry];"
                            + " ticket READY after 0 write(s)",
                    "throws: TransitionAbortedException with the thrown exception,"
                            + " journal [publish FAILED, schedule retry THREW], calls [publish, schedule retry];"
                            + " ticket READY after 0 write(s)",
                    "times out: TransitionAbortedException with TimeoutException,"
                            + " journal [publish FAILED, schedule retry THREW], calls [publish, schedule retry];"
                            + " ticket READY after 0 write(s)"), ends);
            for (Ticket ticket : failedTickets) {
                assertEquals(TicketState.READY, ticket.state, ticket.id);
                assertEquals(0, ticket.stateWrites, ticket.id);
            }
        });
    }
}
