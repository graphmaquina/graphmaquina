package io.github.graphmaquina.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.AsyncTransitionAction;
import io.github.graphmaquina.api.FailureKind;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.OutcomeCode;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.error.UnhandledFailureException;
import io.github.graphmaquina.fixtures.RecoveryFixtures;
import io.github.graphmaquina.fixtures.RecoveryFixtures.ProviderRejectedException;
import io.github.graphmaquina.fixtures.RecoveryFixtures.PublicationFailures;
import io.github.graphmaquina.fixtures.RecoveryFixtures.ServiceUnavailableException;
import io.github.graphmaquina.fixtures.RecoveryFixtures.Ticket;
import io.github.graphmaquina.fixtures.RecoveryFixtures.TicketChange;
import io.github.graphmaquina.fixtures.RecoveryFixtures.TicketState;
import io.github.graphmaquina.fixtures.Sneaky;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Typed recovery on the asynchronous paths: the route a failure selects. A returned failure kind, an exception thrown
 * before the stage exists and an exceptional completion select the same recovery, with the same failure kind, cause
 * and input, in a direct call and through the async facade, with a synchronous or an asynchronous action. The
 * engine's own wait failures never reach a rule, whatever type the rule lists, and leave the action's stage running,
 * and a stage that is cancelled aborts the call unrouted. Only the exact wrapper classes are unwrapped, however they
 * arrive, and two publications in flight keep their own recovery. The recovery that follows is read in
 * {@link AsyncRecoveryEdgeTest}. Each test has an independent preemptive watchdog and finally cleanup of pending
 * stages, facade calls and test-owned workers, which {@link AsyncRecoveryHarness} provides.
 */
class AsyncRecoveryTest extends AsyncRecoveryHarness {

    /** The input of a publication: the request it serves. */
    record Request(String id) { }

    /** One way to publish {@code ticket} with {@code input}: which action PUBLISH runs and how the call is made. */
    private interface Publication {
        Outcome<Ticket, TicketState, TicketChange> publish(Ticket ticket, Request input) throws Exception;
    }

    /** A subclass of CompletionException: not the exact class the engine unwraps. */
    static final class WrappedCompletion extends CompletionException {
        WrappedCompletion(Throwable cause) {
            super("wrapped completion", cause);
        }
    }

    /** A subclass of ExecutionException: not the exact class the engine unwraps. */
    static final class WrappedExecution extends ExecutionException {
        WrappedExecution(Throwable cause) {
            super("wrapped execution", cause);
        }
    }

    /**
     * Publishes {@code ticket} with {@code input} through the async facade over {@link #tickets(UnaryOperator)}.
     * PUBLISH logs "publish" and returns a stage that stays pending until handle is registered against it; the test
     * then completes it with {@code completion}. The {@code rules} declare what PUBLISH recovers from.
     */
    private Outcome<Ticket, TicketState, TicketChange> publishLater(
            Ticket ticket, Object input, UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>> rules,
            Consumer<CompletableFuture<ActionOutcome>> completion) throws Exception {
        RegisteredStage<ActionOutcome> stage = registeredStage();
        StateMachine<Ticket, TicketState, TicketChange> machine = machine(tickets(
                (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> rules.apply(publish.asyncAction(
                        "publish", (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> {
                            ctx.entity().calls.add("publish");
                            return stage;
                        }))));
        CompletableFuture<Outcome<Ticket, TicketState, TicketChange>> call =
                start(machine, ticket, input);
        stage.awaitPendingRegistration();
        completion.accept(stage);
        return call.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    /** {@link #routed}, followed by whether the recovery action received {@code input}. */
    private String recovered(Ticket ticket, Object input, Throwable thrown,
                             Outcome<Ticket, TicketState, TicketChange> outcome) {
        Object got = outcome.asFailed()
                .map((Outcome.Failed<Ticket, TicketState, TicketChange> failed) ->
                        received.get(ticket.id + " " + failed.failureTransition()))
                .orElse(null);
        return routed(ticket, thrown, outcome) + "; its recovery got "
                + (got == input ? "the call's input" : String.valueOf(got));
    }

    /**
     * How a call that should abort ended: the type of what it threw and whether its cause is {@code cause}, or, if it
     * returned, its code; then the ticket's state and writes.
     */
    private static String aborted(Ticket ticket, Throwable cause, Publication call) {
        String how;
        try {
            how = "returned " + call.publish(ticket, new Request(ticket.id)).code();
        } catch (Exception e) {
            Throwable failure = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            how = failure.getClass().getSimpleName() + " caused by "
                    + (failure.getCause() == cause ? "the expected cause" : String.valueOf(failure.getCause()));
        }
        return how + "; ticket " + ticket.state + " after " + ticket.stateWrites + " write(s)";
    }

    /**
     * Nothing was routed: the ticket is still READY, never written, and only PUBLISH ran. The stage is neither
     * cancelled nor complete; completing it now, late, writes nothing either.
     */
    private static void assertNothingRoutedAndTheStageLeftAlive(Ticket ticket, CompletableFuture<ActionOutcome> stage) {
        assertEquals(List.of("publish"), ticket.calls);
        assertEquals(TicketState.READY, ticket.state);
        assertEquals(0, ticket.stateWrites);
        assertFalse(stage.isDone(), "the engine completed or cancelled the action's stage");
        stage.complete(ActionOutcome.failed(PublicationFailures.Unavailable.class, "late answer"));
        assertEquals(List.of("publish"), ticket.calls);
        assertEquals(TicketState.READY, ticket.state);
        assertEquals(0, ticket.stateWrites);
    }

    /**
     * Two CompletionExceptions, each the cause of the other. Their public constructors set the cause once and for
     * all, so the pair is written with a placeholder for the outer one and read back with the outer one in its place.
     */
    private static CompletionException cycle() throws IOException, ClassNotFoundException {
        RuntimeException placeholder = new RuntimeException("stands for the outer wrapper");
        CompletionException outer = new CompletionException("outer", new CompletionException("inner", placeholder));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes) {
            {
                enableReplaceObject(true);
            }

            @Override
            protected Object replaceObject(Object written) {
                return written == placeholder ? outer : written;
            }
        }) {
            out.writeObject(outer);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (CompletionException) in.readObject();
        }
    }

    /**
     * One literal row of the returned-failure routing table, with the state and route expected independently of the
     * selector implementation.
     */
    private record ReturnedCase(String name, Class<? extends FailureKind> kind, boolean fallback,
                                TicketChange route, TicketState state) { }

    /**
     * The whole returned-failure routing table through the async facade: synchronous, already completed and pending
     * asynchronous actions.
     */
    @Test
    void everyReturnedFailureRowKeepsItsRouteKindAndInputOnEveryAsyncPath() throws Exception {
        bounded(() -> {
            for (ReturnedCase row : returnedCases()) {
                for (String path : List.of("sync facade", "completed stage", "pending stage")) {
                    assertTheRowOnThePath(row, path);
                }
            }
        });
    }

    private static List<ReturnedCase> returnedCases() {
        return List.of(
                new ReturnedCase("untyped with fallback", null, true,
                        TicketChange.MARK_INTEGRATION_FAILED, TicketState.INTEGRATION_FAILED),
                new ReturnedCase("untyped without fallback", null, false, null, TicketState.READY),
                new ReturnedCase("known with fallback", PublicationFailures.Rejected.class, true,
                        TicketChange.RECORD_REJECTION, TicketState.REJECTED_BY_PROVIDER),
                new ReturnedCase("known without fallback", PublicationFailures.Rejected.class, false,
                        TicketChange.RECORD_REJECTION, TicketState.REJECTED_BY_PROVIDER),
                new ReturnedCase("unknown with fallback", PublicationFailures.Unavailable.class, true,
                        TicketChange.MARK_INTEGRATION_FAILED, TicketState.INTEGRATION_FAILED),
                new ReturnedCase("unknown without fallback", PublicationFailures.Unavailable.class, false,
                        null, TicketState.READY));
    }

    private void assertTheRowOnThePath(ReturnedCase row, String path) throws Exception {
        String scenario = row.name() + ", " + path;
        Ticket ticket = new Ticket(scenario);
        Request input = new Request(scenario);
        ActionOutcome result = row.kind() == null ? ActionOutcome.failed("provider refused")
                : ActionOutcome.failed(row.kind(), "provider refused");
        RegisteredStage<ActionOutcome> stage = registeredStage();
        CompletableFuture<Outcome<Ticket, TicketState, TicketChange>> call = start(
                machine(tickets(returningTheFailure(row, path, input, result, stage))), ticket, input);
        if (path.equals("pending stage")) {
            stage.awaitPendingRegistration();
            stage.complete(result);
        }
        if (row.route() == null) {
            assertTheFailureIsUnhandled(call, ticket, scenario);
        } else {
            assertTheFailureIsRouted(row, call, ticket, input, scenario);
        }
        assertEquals(row.state(), ticket.state, scenario);
    }

    /**
     * PUBLISH returns {@code result}, which its rule routes to RECORD_REJECTION when its kind is Rejected, with the
     * fallback of the row when it has one; {@code path} says how: a synchronous action, an asynchronous one whose stage
     * is complete, or one whose {@code stage} is pending.
     */
    private static UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>> returningTheFailure(
            ReturnedCase row, String path, Request input, ActionOutcome result, RegisteredStage<ActionOutcome> stage) {
        return (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> {
            TransitionBuilder<Ticket, TicketState, TicketChange> configured;
            if (path.equals("sync facade")) {
                configured = publish.action("publish",
                        (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> {
                            assertSame(input, ctx.input(Request.class).orElseThrow());
                            ctx.entity().calls.add("publish");
                            return result;
                        });
            } else {
                configured = publish.asyncAction("publish",
                        (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> {
                            assertSame(input, ctx.input(Request.class).orElseThrow());
                            ctx.entity().calls.add("publish");
                            return path.equals("completed stage")
                                    ? CompletableFuture.completedFuture(result) : stage;
                        });
            }
            configured = configured.recover(PublicationFailures.Rejected.class, TicketChange.RECORD_REJECTION);
            return row.fallback() ? configured.onFailure(TicketChange.MARK_INTEGRATION_FAILED) : configured;
        };
    }

    private static void assertTheFailureIsUnhandled(
            CompletableFuture<Outcome<Ticket, TicketState, TicketChange>> call, Ticket ticket, String scenario)
            throws Exception {
        ExecutionException transport = assertThrows(ExecutionException.class,
                () -> call.get(WAIT_SECONDS, TimeUnit.SECONDS), scenario);
        UnhandledFailureException error = assertInstanceOf(UnhandledFailureException.class,
                transport.getCause(), scenario);
        assertEquals("provider refused", error.reason(), scenario);
        assertSame(null, error.getCause(), scenario);
        assertEquals(List.of("publish FAILED"), journal(error), scenario);
        assertEquals(List.of("publish"), ticket.calls, scenario);
        assertEquals(0, ticket.stateWrites, scenario);
    }

    private void assertTheFailureIsRouted(ReturnedCase row,
            CompletableFuture<Outcome<Ticket, TicketState, TicketChange>> call, Ticket ticket, Request input,
            String scenario) throws Exception {
        Outcome<Ticket, TicketState, TicketChange> outcome = call.get(WAIT_SECONDS, TimeUnit.SECONDS);
        Outcome.Failed<Ticket, TicketState, TicketChange> failed = outcome.asFailed().orElseThrow();
        assertEquals(OutcomeCode.FAILURE_ROUTED, outcome.code(), scenario);
        assertEquals(row.route(), failed.failureTransition(), scenario);
        assertEquals(row.state(), failed.movedTo(), scenario);
        assertEquals(Optional.ofNullable(row.kind()), failed.failureKind(), scenario);
        assertEquals(Optional.empty(), failed.cause(), scenario);
        assertEquals("provider refused", failed.reason(), scenario);
        assertSame(input, received.get(ticket.id + " " + row.route()), scenario);
        assertEquals(1, ticket.stateWrites, scenario);
        assertEquals(1, outcome.records().size(), scenario);
        assertEquals(row.route(), outcome.records().getFirst().transition(), scenario);
    }

    /** A declared throwable whose message must never be read before an existing poison wins. */
    private static final class UnreadableFailure extends RuntimeException {
        private int reads;

        @Override
        public String getMessage() {
            reads++;
            throw new IllegalStateException("message must not be read");
        }
    }

    /** Exceptional completed and pending stages preserve an existing poison without inspecting the new message. */
    @Test
    void exceptionalStageMessagesAreNotReadBeforeThePoisonCheck() throws Exception {
        bounded(() -> {
            for (boolean alreadyCompleted : List.of(true, false)) {
                assertThePoisonWinsBeforeTheMessageIsRead(alreadyCompleted);
            }
        });
    }

    /**
     * The action of a root ticket cascades into a peer whose own action fails with an IOException, and catches the
     * abort that poisons the call; it then returns a stage that fails with an {@link UnreadableFailure}, at once when
     * {@code alreadyCompleted} and otherwise once the engine registered on it.
     */
    private void assertThePoisonWinsBeforeTheMessageIsRead(boolean alreadyCompleted) throws Exception {
        Ticket peer = new Ticket("peer");
        Ticket root = new Ticket("root");
        UnreadableFailure unreadable = new UnreadableFailure();
        RegisteredStage<ActionOutcome> stage = registeredStage();
        CompletableFuture<TransitionAbortedException> poisoned = pending();
        StateMachine<Ticket, TicketState, TicketChange> machine = machine(tickets(
                (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> publish
                        .asyncAction("publish", (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> {
                            ctx.entity().calls.add("publish");
                            if (ctx.entity() == peer) {
                                throw Sneaky.raise(new IOException("peer failed"));
                            }
                            try {
                                ctx.cascade(RecoveryFixtures.KEY, peer, TicketChange.PUBLISH);
                            } catch (TransitionAbortedException poison) {
                                poisoned.complete(poison);
                                if (alreadyCompleted) {
                                    stage.completeExceptionally(unreadable);
                                }
                                return stage;
                            }
                            throw new AssertionError("the peer must abort");
                        })
                        .recover(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY,
                                UnreadableFailure.class)
                        .onFailure(TicketChange.MARK_INTEGRATION_FAILED)));
        CompletableFuture<Outcome<Ticket, TicketState, TicketChange>> call = start(machine, root);
        TransitionAbortedException poison = poisoned.get(WAIT_SECONDS, TimeUnit.SECONDS);
        if (!alreadyCompleted) {
            stage.awaitPendingRegistration();
            stage.completeExceptionally(unreadable);
        }
        ExecutionException transport = assertThrows(ExecutionException.class,
                () -> call.get(WAIT_SECONDS, TimeUnit.SECONDS));
        assertSame(poison, transport.getCause());
        assertEquals(0, unreadable.reads);
        assertEquals(TicketState.READY, root.state);
        assertEquals(TicketState.READY, peer.state);
        assertEquals(0, root.stateWrites + peer.stateWrites);
        assertEquals(List.of("publish"), root.calls);
    }

    /**
     * A returned failure kind selects its rule on every path: a synchronous action and an asynchronous one whose stage
     * is already complete, called directly; and both through the async facade, the asynchronous stage completing
     * while the call waits. Each takes RECORD_REJECTION with the kind Rejected and no cause, and its recovery action
     * receives the very input of the call.
     */
    @Test
    void aReturnedKindSelectsTheSameRecoveryOnEveryPath() throws Exception {
        bounded(() -> {
            UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>> sync =
                    (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> RecoveryFixtures.exampleRules(
                            publish.action("publish", (TransitionContext<Ticket, TicketState, TicketChange> ctx) ->
                                    ActionOutcome.failed(PublicationFailures.Rejected.class, "content rejected")));
            UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>> async =
                    (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> RecoveryFixtures.exampleRules(
                            publish.asyncAction("publish", (TransitionContext<Ticket, TicketState, TicketChange> ctx) ->
                                    CompletableFuture.completedFuture(ActionOutcome.failed(
                                            PublicationFailures.Rejected.class, "content rejected"))));
            Map<String, Publication> paths = new LinkedHashMap<>();
            paths.put("sync action, direct call", (Ticket ticket, Request input) ->
                    machine(tickets(sync)).execute(ticket, TicketChange.PUBLISH, input));
            paths.put("async action, direct call", (Ticket ticket, Request input) ->
                    machine(tickets(async)).execute(ticket, TicketChange.PUBLISH, input));
            paths.put("sync action, async facade", (Ticket ticket, Request input) ->
                    viaFacade(machine(tickets(sync)), ticket, input));
            paths.put("async action completed while the facade's call waits", (Ticket ticket, Request input) ->
                    publishLater(ticket, input, RecoveryFixtures::exampleRules,
                            (CompletableFuture<ActionOutcome> stage) -> stage.complete(
                                    ActionOutcome.failed(PublicationFailures.Rejected.class, "content rejected"))));
            List<String> ends = new ArrayList<>();
            List<String> expected = new ArrayList<>();
            for (Map.Entry<String, Publication> path : paths.entrySet()) {
                Ticket ticket = new Ticket(path.getKey());
                Request input = new Request(path.getKey());
                ends.add(path.getKey() + ": "
                        + recovered(ticket, input, null, path.getValue().publish(ticket, input)));
                expected.add(path.getKey() + ": FAILURE_ROUTED RECORD_REJECTION to REJECTED_BY_PROVIDER, kind Rejected,"
                        + " no cause; ticket REJECTED_BY_PROVIDER after 1 write(s); its recovery got the call's input");
            }
            assertEquals(expected, ends);
        });
    }

    /**
     * A thrown type selects its rule on every path: thrown by a synchronous action, thrown by an asynchronous one
     * before it returns its stage, and completing the stage, called directly and through the async facade. Each takes
     * RECORD_REJECTION with the kind Rejected and the thrown exception itself as cause, and its recovery action
     * receives the very input of the call.
     */
    @Test
    void aThrownTypeSelectsTheSameRecoveryOnEveryPath() throws Exception {
        bounded(() -> {
            ProviderRejectedException rejected = new ProviderRejectedException("content rejected");
            UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>> syncThrows =
                    (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> RecoveryFixtures.exampleRules(
                            publish.action("publish", (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> {
                                throw rejected;
                            }));
            UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>> asyncThrows =
                    (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> RecoveryFixtures.exampleRules(
                            publish.asyncAction("publish",
                                    (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> {
                                        throw rejected;
                                    }));
            UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>> asyncFails =
                    (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> RecoveryFixtures.exampleRules(
                            publish.asyncAction("publish", (TransitionContext<Ticket, TicketState, TicketChange> ctx) ->
                                    CompletableFuture.<ActionOutcome>failedFuture(rejected)));
            Map<String, Publication> paths = new LinkedHashMap<>();
            paths.put("sync action throws, direct call", (Ticket ticket, Request input) ->
                    machine(tickets(syncThrows)).execute(ticket, TicketChange.PUBLISH, input));
            paths.put("async action throws, direct call", (Ticket ticket, Request input) ->
                    machine(tickets(asyncThrows)).execute(ticket, TicketChange.PUBLISH, input));
            paths.put("async stage fails, direct call", (Ticket ticket, Request input) ->
                    machine(tickets(asyncFails)).execute(ticket, TicketChange.PUBLISH, input));
            paths.put("sync action throws, async facade", (Ticket ticket, Request input) ->
                    viaFacade(machine(tickets(syncThrows)), ticket, input));
            paths.put("async action throws, async facade", (Ticket ticket, Request input) ->
                    viaFacade(machine(tickets(asyncThrows)), ticket, input));
            paths.put("async stage fails while the facade's call waits", (Ticket ticket, Request input) ->
                    publishLater(ticket, input, RecoveryFixtures::exampleRules,
                            (CompletableFuture<ActionOutcome> stage) -> stage.completeExceptionally(rejected)));
            List<String> ends = new ArrayList<>();
            List<String> expected = new ArrayList<>();
            for (Map.Entry<String, Publication> path : paths.entrySet()) {
                Ticket ticket = new Ticket(path.getKey());
                Request input = new Request(path.getKey());
                ends.add(path.getKey() + ": "
                        + recovered(ticket, input, rejected, path.getValue().publish(ticket, input)));
                expected.add(path.getKey() + ": FAILURE_ROUTED RECORD_REJECTION to REJECTED_BY_PROVIDER, kind Rejected,"
                        + " the thrown exception; ticket REJECTED_BY_PROVIDER after 1 write(s); its recovery got the"
                        + " call's input");
            }
            assertEquals(expected, ends);
        });
    }

    /**
     * A checked exception that a rule lists is routed when it completes the stage: bare, inside an exact
     * CompletionException or inside an exact ExecutionException, called directly or through the async facade.
     */
    @Test
    void aCheckedExceptionCompletingTheStageIsRoutedByItsRule() throws Exception {
        bounded(() -> {
            IOException reset = new IOException("connection reset");
            UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>> rules =
                    (TransitionBuilder<Ticket, TicketState, TicketChange> publish) ->
                            publish.recover(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY,
                                    IOException.class);
            Map<String, Throwable> completions = new LinkedHashMap<>();
            completions.put("bare", reset);
            completions.put("in a CompletionException", new CompletionException(reset));
            completions.put("in an ExecutionException", new ExecutionException(reset));
            Map<String, Publication> paths = new LinkedHashMap<>();
            for (Map.Entry<String, Throwable> completion : completions.entrySet()) {
                paths.put(completion.getKey() + ", direct call", (Ticket ticket, Request input) ->
                        machine(tickets((TransitionBuilder<Ticket, TicketState, TicketChange> publish) ->
                                rules.apply(publish.asyncAction("publish",
                                        (TransitionContext<Ticket, TicketState, TicketChange> ctx) ->
                                                CompletableFuture.<ActionOutcome>failedFuture(completion.getValue())))))
                                .execute(ticket, TicketChange.PUBLISH, input));
                paths.put(completion.getKey() + ", while the facade's call waits", (Ticket ticket, Request input) ->
                        publishLater(ticket, input, rules, (CompletableFuture<ActionOutcome> stage) ->
                                stage.completeExceptionally(completion.getValue())));
            }
            List<String> ends = new ArrayList<>();
            List<String> expected = new ArrayList<>();
            for (Map.Entry<String, Publication> path : paths.entrySet()) {
                Ticket ticket = new Ticket(path.getKey());
                Request input = new Request(path.getKey());
                ends.add(path.getKey() + ": "
                        + recovered(ticket, input, reset, path.getValue().publish(ticket, input)));
                expected.add(path.getKey() + ": FAILURE_ROUTED SCHEDULE_RETRY to RETRY_PENDING, kind Unavailable, the"
                        + " thrown exception; ticket RETRY_PENDING after 1 write(s);"
                        + " its recovery got the call's input");
            }
            assertEquals(expected, ends);
        });
    }

    /**
     * The engine's own timeout of a wait is no failure of the action: a rule that lists TimeoutException does not
     * route it. The call aborts with the TimeoutException as cause, the stage is left running, and its late
     * completion writes nothing.
     */
    @Test
    void anEngineTimeoutIsNeverRoutedEvenByARuleThatListsTimeoutException() {
        bounded(() -> {
            CompletableFuture<ActionOutcome> stage = pending();
            StateMachine<Ticket, TicketState, TicketChange> machine = machine(RecoveryFixtures.graph(
                    (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> publish
                            .asyncAction("publish", (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> {
                                ctx.entity().calls.add("publish");
                                return stage;
                            })
                            .timeout(Duration.ofMillis(100))
                            .recover(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY,
                                    TimeoutException.class)));
            Ticket ticket = new Ticket("t1");
            TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                    () -> machine.execute(ticket, TicketChange.PUBLISH));
            assertInstanceOf(TimeoutException.class, error.getCause());
            assertEquals(Optional.of(TicketChange.PUBLISH), error.transition());
            assertNothingRoutedAndTheStageLeftAlive(ticket, stage);
        });
    }

    /** By contrast, a TimeoutException the action reports itself, a client's own timeout, is a declared failure. */
    @Test
    void aTimeoutTheActionReportsItselfIsRoutedByItsRule() {
        bounded(() -> {
            TimeoutException clientTimeout = new TimeoutException("the provider did not answer in time");
            StateMachine<Ticket, TicketState, TicketChange> machine = machine(RecoveryFixtures.graph(
                    (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> publish
                            .asyncAction("publish", (TransitionContext<Ticket, TicketState, TicketChange> ctx) ->
                                    CompletableFuture.<ActionOutcome>failedFuture(clientTimeout))
                            .recover(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY,
                                    TimeoutException.class)));
            Ticket ticket = new Ticket("t1");
            assertEquals("FAILURE_ROUTED SCHEDULE_RETRY to RETRY_PENDING, kind Unavailable, the thrown exception;"
                            + " ticket RETRY_PENDING after 1 write(s)",
                    routed(ticket, clientTimeout, machine.execute(ticket, TicketChange.PUBLISH)));
        });
    }

    /**
     * An interrupt that ends the engine's wait is no failure of the action either: a rule that lists
     * InterruptedException does not route it. The call runs on the async facade's own thread, which the action
     * interrupts before it returns a stage that is not complete.
     */
    @Test
    void anInterruptedWaitIsNeverRoutedEvenByARuleThatListsInterruptedException() {
        bounded(() -> {
            CompletableFuture<ActionOutcome> stage = pending();
            StateMachine<Ticket, TicketState, TicketChange> machine = machine(RecoveryFixtures.graph(
                    (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> publish
                            .asyncAction("publish", (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> {
                                ctx.entity().calls.add("publish");
                                Thread.currentThread().interrupt();
                                return stage;
                            })
                            .recover(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY,
                                    InterruptedException.class)));
            Ticket ticket = new Ticket("t1");
            CompletableFuture<Outcome<Ticket, TicketState, TicketChange>> call =
                    start(machine, ticket);
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> call.get(WAIT_SECONDS, TimeUnit.SECONDS));
            TransitionAbortedException error = assertInstanceOf(TransitionAbortedException.class, failure.getCause());
            assertInstanceOf(InterruptedException.class, error.getCause());
            assertNothingRoutedAndTheStageLeftAlive(ticket, stage);
        });
    }

    /**
     * A wait that the thread's ForkJoinPool refuses to let block is no failure of the action either: a rule that
     * lists RejectedExecutionException does not route it. The call runs on the only worker of a pool that may not
     * start a spare one.
     */
    @Test
    void aWaitThePoolRefusesIsNeverRoutedEvenByARuleThatListsRejectedExecutionException() throws Exception {
        bounded(() -> {
            CompletableFuture<ActionOutcome> stage = pending();
            StateMachine<Ticket, TicketState, TicketChange> machine = machine(RecoveryFixtures.graph(
                    (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> publish
                            .asyncAction("publish", (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> {
                                ctx.entity().calls.add("publish");
                                return stage;
                            })
                            .recover(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY,
                                    RejectedExecutionException.class)));
            Ticket ticket = new Ticket("t1");
            Throwable thrown;
            ForkJoinPool pool = capped();
            try {
                CompletableFuture<Throwable> call = CompletableFuture.supplyAsync(() -> {
                    try {
                        machine.execute(ticket, TicketChange.PUBLISH);
                        return null;
                    } catch (RuntimeException e) {
                        return e;
                    }
                }, pool);
                calls.add(call);
                thrown = call.get(WAIT_SECONDS, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }
            TransitionAbortedException error = assertInstanceOf(TransitionAbortedException.class, thrown);
            assertInstanceOf(RejectedExecutionException.class, error.getCause());
            assertNothingRoutedAndTheStageLeftAlive(ticket, stage);
        });
    }

    /**
     * The exact wrapper classes are unwrapped on every asynchronous path, nested or not, thrown before the stage or
     * completing it: the exception a rule lists is what selects the recovery and what the outcome carries.
     */
    @Test
    void exactWrappersAreUnwrappedBeforeTheRulesAreRead() {
        bounded(() -> {
            ServiceUnavailableException down = new ServiceUnavailableException("provider down");
            Map<String, AsyncTransitionAction<Ticket, TicketState, TicketChange>> actions = new LinkedHashMap<>();
            actions.put("a CompletionException thrown before the stage",
                    (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> {
                        throw new CompletionException(down);
                    });
            actions.put("an ExecutionException thrown before the stage",
                    (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> {
                        throw Sneaky.raise(new ExecutionException(down));
                    });
            actions.put("a CompletionException completing the stage",
                    (TransitionContext<Ticket, TicketState, TicketChange> ctx) ->
                            CompletableFuture.failedFuture(new CompletionException(down)));
            actions.put("an ExecutionException completing the stage",
                    (TransitionContext<Ticket, TicketState, TicketChange> ctx) ->
                            CompletableFuture.failedFuture(new ExecutionException(down)));
            actions.put("nested wrappers completing the stage",
                    (TransitionContext<Ticket, TicketState, TicketChange> ctx) ->
                            CompletableFuture.failedFuture(new CompletionException(new ExecutionException(down))));
            List<String> ends = new ArrayList<>();
            List<String> expected = new ArrayList<>();
            for (Map.Entry<String, AsyncTransitionAction<Ticket, TicketState, TicketChange>> action
                    : actions.entrySet()) {
                Ticket ticket = new Ticket(action.getKey());
                Outcome<Ticket, TicketState, TicketChange> outcome = machine(
                        RecoveryFixtures.exampleAsyncGraph(action.getValue())).execute(ticket, TicketChange.PUBLISH);
                ends.add(action.getKey() + ": " + routed(ticket, down, outcome));
                expected.add(action.getKey() + ": FAILURE_ROUTED SCHEDULE_RETRY to RETRY_PENDING, kind Unavailable, the"
                        + " thrown exception; ticket RETRY_PENDING after 1 write(s)");
            }
            assertEquals(expected, ends);
        });
    }

    /**
     * A subclass of either wrapper is not unwrapped: it is the very throwable the rules see, which none lists, so the
     * call aborts with it as cause and nothing is written. The action throws it before returning its stage, or, for
     * the ExecutionException subclass, completes the stage with it.
     */
    @Test
    void wrapperSubclassesAreNotUnwrapped() {
        bounded(() -> {
            ServiceUnavailableException down = new ServiceUnavailableException("provider down");
            Map<String, Throwable> wrappers = new LinkedHashMap<>();
            wrappers.put("a CompletionException subclass thrown before the stage", new WrappedCompletion(down));
            wrappers.put("an ExecutionException subclass thrown before the stage", new WrappedExecution(down));
            wrappers.put("an ExecutionException subclass completing the stage", new WrappedExecution(down));
            List<String> ends = new ArrayList<>();
            List<String> expected = new ArrayList<>();
            for (Map.Entry<String, Throwable> wrapper : wrappers.entrySet()) {
                AsyncTransitionAction<Ticket, TicketState, TicketChange> action =
                        wrapper.getKey().endsWith("completing the stage")
                                ? (TransitionContext<Ticket, TicketState, TicketChange> ctx) ->
                                        CompletableFuture.failedFuture(wrapper.getValue())
                                : (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> {
                                    throw Sneaky.raise(wrapper.getValue());
                                };
                StateMachine<Ticket, TicketState, TicketChange> machine =
                        machine(RecoveryFixtures.exampleAsyncGraph(action));
                ends.add(wrapper.getKey() + ": " + aborted(new Ticket(wrapper.getKey()), wrapper.getValue(),
                        (Ticket ticket, Request input) -> machine.execute(ticket, TicketChange.PUBLISH)));
                expected.add(wrapper.getKey() + ": TransitionAbortedException caused by the expected cause;"
                        + " ticket READY after 0 write(s)");
            }
            assertEquals(expected, ends);
        });
    }

    /**
     * On the asynchronous path, a CompletionException subclass that completes the stage is not unwrapped. Its cause
     * is a type a rule lists, yet the call aborts with the subclass itself as cause, as it does when the action
     * throws the same wrapper, rather than route by the cause.
     */
    @Test
    void aCompletionExceptionSubclassCompletingTheStageIsNotUnwrapped() {
        bounded(() -> {
            WrappedCompletion wrapper = new WrappedCompletion(new ServiceUnavailableException("provider down"));
            StateMachine<Ticket, TicketState, TicketChange> machine = machine(
                    RecoveryFixtures.exampleAsyncGraph((TransitionContext<Ticket, TicketState, TicketChange> ctx) ->
                            CompletableFuture.failedFuture(wrapper)));
            assertEquals("TransitionAbortedException caused by the expected cause; ticket READY after 0 write(s)",
                    aborted(new Ticket("t1"), wrapper,
                            (Ticket ticket, Request input) -> machine.execute(ticket, TicketChange.PUBLISH)));
        });
    }

    /**
     * An exact wrapper without a cause has nothing to unwrap: it is itself the throwable the rules see, which none
     * lists, so the call aborts with it as cause, whether it completes the stage or is thrown before it.
     */
    @Test
    void anExactWrapperWithoutACauseIsTheCauseOfTheAbort() {
        bounded(() -> {
            Map<String, Throwable> wrappers = new LinkedHashMap<>();
            wrappers.put("a CompletionException completing the stage", new CompletionException("no cause", null));
            wrappers.put("an ExecutionException completing the stage", new ExecutionException("no cause", null));
            wrappers.put("a CompletionException thrown before the stage", new CompletionException("no cause", null));
            wrappers.put("an ExecutionException thrown before the stage", new ExecutionException("no cause", null));
            List<String> ends = new ArrayList<>();
            List<String> expected = new ArrayList<>();
            for (Map.Entry<String, Throwable> wrapper : wrappers.entrySet()) {
                AsyncTransitionAction<Ticket, TicketState, TicketChange> action =
                        wrapper.getKey().endsWith("completing the stage")
                                ? (TransitionContext<Ticket, TicketState, TicketChange> ctx) ->
                                        CompletableFuture.failedFuture(wrapper.getValue())
                                : (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> {
                                    throw Sneaky.raise(wrapper.getValue());
                                };
                StateMachine<Ticket, TicketState, TicketChange> machine =
                        machine(RecoveryFixtures.exampleAsyncGraph(action));
                ends.add(wrapper.getKey() + ": " + aborted(new Ticket(wrapper.getKey()), wrapper.getValue(),
                        (Ticket ticket, Request input) -> machine.execute(ticket, TicketChange.PUBLISH)));
                expected.add(wrapper.getKey() + ": TransitionAbortedException caused by the expected cause;"
                        + " ticket READY after 0 write(s)");
            }
            assertEquals(expected, ends);
        });
    }

    /**
     * A chain of causes that loops back ends the unwrap. A CompletionException whose cause is a
     * ServiceUnavailableException whose cause is that wrapper again completes the stage: the unwrap stops at the
     * exception the rule lists, which routes. Two CompletionExceptions that are each other's cause hold no such
     * exception: the unwrap stops at a wrapper of the loop, which no rule lists, and the call aborts with it as
     * cause, whether the action throws the loop or completes its stage with it. Every call runs under a timeout, so
     * an unwrap that never ends fails the test instead of hanging the build.
     */
    @Test
    void aLoopInTheCauseChainEndsTheUnwrap() throws Exception {
        bounded(() -> {
            ServiceUnavailableException looped = new ServiceUnavailableException("provider down");
            CompletionException wrapper = new CompletionException(looped);
            looped.initCause(wrapper);
            StateMachine<Ticket, TicketState, TicketChange> routing = machine(
                    RecoveryFixtures.exampleAsyncGraph((TransitionContext<Ticket, TicketState, TicketChange> ctx) ->
                            CompletableFuture.failedFuture(wrapper)));
            Ticket routedTicket = new Ticket("routed");
            Outcome<Ticket, TicketState, TicketChange> outcome = assertTimeoutPreemptively(
                    Duration.ofSeconds(WAIT_SECONDS), () -> routing.execute(routedTicket, TicketChange.PUBLISH));
            assertEquals("FAILURE_ROUTED SCHEDULE_RETRY to RETRY_PENDING, kind Unavailable, the thrown exception;"
                    + " ticket RETRY_PENDING after 1 write(s)", routed(routedTicket, looped, outcome));
            CompletionException loop = cycle();
            assertSame(loop, loop.getCause().getCause(), "the two wrappers must be each other's cause");
            Map<String, AsyncTransitionAction<Ticket, TicketState, TicketChange>> actions = new LinkedHashMap<>();
            actions.put("thrown before the stage", (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> {
                throw loop;
            });
            actions.put("completing the stage", (TransitionContext<Ticket, TicketState, TicketChange> ctx) ->
                    CompletableFuture.failedFuture(loop));
            List<String> ends = new ArrayList<>();
            for (Map.Entry<String, AsyncTransitionAction<Ticket, TicketState, TicketChange>> action
                    : actions.entrySet()) {
                StateMachine<Ticket, TicketState, TicketChange> machine =
                        machine(RecoveryFixtures.exampleAsyncGraph(action.getValue()));
                Ticket ticket = new Ticket(action.getKey());
                TransitionAbortedException error = assertTimeoutPreemptively(Duration.ofSeconds(WAIT_SECONDS),
                        () -> assertThrows(TransitionAbortedException.class,
                                () -> machine.execute(ticket, TicketChange.PUBLISH)));
                Throwable cause = error.getCause();
                ends.add(action.getKey() + ": aborted by "
                        + (cause == loop || cause == loop.getCause() ? "a wrapper of the loop" : String.valueOf(cause))
                        + "; ticket " + ticket.state + " after " + ticket.stateWrites + " write(s)");
            }
            assertEquals(List.of(
                    "thrown before the stage: aborted by a wrapper of the loop; ticket READY after 0 write(s)",
                    "completing the stage: aborted by a wrapper of the loop; ticket READY after 0 write(s)"), ends);
        });
    }

    /**
     * Review Focus 1: two publications in flight on distinct tickets, each through the async facade with its own
     * input, completed in the reverse order of their start. The second completes first, with the kind Rejected, and
     * takes RECORD_REJECTION while the first still waits; the first then fails with a ServiceUnavailableException
     * and takes SCHEDULE_RETRY. Each keeps its own recovery, failure kind, cause and input.
     */
    @Test
    void twoPublicationsCompletedInReverseOrderKeepTheirOwnRecovery() throws Exception {
        bounded(() -> {
            Map<String, RegisteredStage<ActionOutcome>> stages =
                    Map.of("first", registeredStage(), "second", registeredStage());
            StateMachine<Ticket, TicketState, TicketChange> machine = machine(tickets(
                    (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> RecoveryFixtures.exampleRules(
                            publish.asyncAction("publish",
                                    (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> {
                                        ctx.entity().calls.add("publish");
                                        return stages.get(ctx.entity().id);
                                    }))), Duration.ofMinutes(1));
            Ticket first = new Ticket("first");
            Ticket second = new Ticket("second");
            Request firstInput = new Request("first request");
            Request secondInput = new Request("second request");
            CompletableFuture<Outcome<Ticket, TicketState, TicketChange>> firstCall =
                    start(machine, first, firstInput);
            stages.get("first").awaitPendingRegistration();
            CompletableFuture<Outcome<Ticket, TicketState, TicketChange>> secondCall =
                    start(machine, second, secondInput);
            stages.get("second").awaitPendingRegistration();
            stages.get("second").complete(ActionOutcome.failed(PublicationFailures.Rejected.class, "content rejected"));
            Outcome<Ticket, TicketState, TicketChange> secondOutcome = secondCall.get(WAIT_SECONDS, TimeUnit.SECONDS);
            assertFalse(firstCall.isDone(), "the first publication must still be waiting for its own stage");
            ServiceUnavailableException down = new ServiceUnavailableException("provider down");
            stages.get("first").completeExceptionally(down);
            Outcome<Ticket, TicketState, TicketChange> firstOutcome = firstCall.get(WAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals("FAILURE_ROUTED SCHEDULE_RETRY to RETRY_PENDING, kind Unavailable, the thrown exception;"
                            + " ticket RETRY_PENDING after 1 write(s); its recovery got the call's input",
                    recovered(first, firstInput, down, firstOutcome));
            assertEquals("FAILURE_ROUTED RECORD_REJECTION to REJECTED_BY_PROVIDER, kind Rejected, no cause;"
                            + " ticket REJECTED_BY_PROVIDER after 1 write(s); its recovery got the call's input",
                    recovered(second, secondInput, null, secondOutcome));
            assertEquals(List.of("publish", "schedule retry"), first.calls);
            assertEquals(List.of("publish", "record rejection"), second.calls);
        });
    }

    /**
     * PUBLISH over IllegalStateException, declared in a recovery rule to SCHEDULE_RETRY or in failsOn; either way
     * with an onFailure. The keys are the scenario names.
     */
    private static Map<String, UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>>>
            overIllegalState() {
        Map<String, UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>>> configurations =
                new LinkedHashMap<>();
        configurations.put("rule", (TransitionBuilder<Ticket, TicketState, TicketChange> publish) ->
                publish.recover(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY,
                        IllegalStateException.class).onFailure(TicketChange.MARK_INTEGRATION_FAILED));
        configurations.put("failsOn", (TransitionBuilder<Ticket, TicketState, TicketChange> publish) ->
                publish.failsOn(IllegalStateException.class).onFailure(TicketChange.MARK_INTEGRATION_FAILED));
        return configurations;
    }

    /**
     * Asserts that {@code call} ended aborted by a CancellationException, with no route taken and no state written.
     * {@code scenario} names the iteration in every failure message.
     */
    private static void assertCancelledAbort(String scenario, Ticket ticket, Executable call) {
        ExecutionException failure = assertThrows(ExecutionException.class, call, scenario);
        TransitionAbortedException aborted =
                assertInstanceOf(TransitionAbortedException.class, failure.getCause(), scenario);
        assertInstanceOf(CancellationException.class, aborted.getCause(), scenario);
        assertEquals(List.of("publish"), ticket.calls, scenario);
        assertEquals(TicketState.READY, ticket.state, scenario);
        assertEquals(0, ticket.stateWrites, scenario);
    }

    /**
     * A stage that is already cancelled when the action returns it aborts the call and is never routed, whether
     * IllegalStateException is declared in a recovery rule or in failsOn, with an onFailure either way.
     */
    @Test
    void anAlreadyCancelledStageAbortsWithoutBeingRouted() {
        bounded(() -> {
            for (Map.Entry<String, UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>>> scenario
                    : overIllegalState().entrySet()) {
                StateMachine<Ticket, TicketState, TicketChange> machine = machine(tickets(
                        (TransitionBuilder<Ticket, TicketState, TicketChange> publish) ->
                                scenario.getValue().apply(publish.asyncAction("publish",
                                        (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> {
                                            ctx.entity().calls.add("publish");
                                            CompletableFuture<ActionOutcome> cancelled = new CompletableFuture<>();
                                            cancelled.cancel(true);
                                            return cancelled;
                                        }))));
                Ticket ticket = new Ticket(scenario.getKey());
                assertCancelledAbort("already cancelled, " + scenario.getKey(), ticket,
                        () -> viaFacade(machine, ticket, new Request(ticket.id)));
            }
        });
    }

    /**
     * A pending stage cancelled after the engine registered on it aborts the call and is never routed, whether
     * IllegalStateException is declared in a recovery rule or in failsOn, with an onFailure either way.
     */
    @Test
    void aPendingStageCancelledLaterAbortsWithoutBeingRouted() {
        bounded(() -> {
            for (Map.Entry<String, UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>>> scenario
                    : overIllegalState().entrySet()) {
                Ticket ticket = new Ticket(scenario.getKey());
                assertCancelledAbort("cancelled while pending, " + scenario.getKey(), ticket,
                        () -> publishLater(ticket, new Request(ticket.id), scenario.getValue(),
                                (CompletableFuture<ActionOutcome> stage) -> stage.cancel(true)));
            }
        });
    }
}
