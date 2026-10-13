package io.github.graphmaquina.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.ActionRecord;
import io.github.graphmaquina.api.AsyncTransitionAction;
import io.github.graphmaquina.api.CommitContext;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.FailureKind;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.OutcomeCode;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.TransitionRecord;
import io.github.graphmaquina.dsl.GraphBuilder;
import io.github.graphmaquina.dsl.StateGraph;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Typed recovery on the asynchronous paths. A returned failure kind, an exception thrown before the stage exists and
 * an exceptional completion select the same recovery, with the same failure kind, cause and input, in a direct call
 * and through the async facade, with a synchronous or an asynchronous action. The engine's own wait failures never
 * reach a rule, whatever type the rule lists, and leave the action's stage running; a recovery waits under the
 * root's budget and under its own timeout. A recovery edge runs as declared: as a move or an action, without its
 * guards, with the call's input and its own hook, and without chaining when it fails. Only the exact wrapper classes
 * are unwrapped, however they arrive, and two publications in flight keep their own recovery. Each test has an
 * independent preemptive watchdog and finally cleanup of pending stages, facade calls and test-owned workers.
 */
class AsyncRecoveryTest {

    /** How long a test waits for a call or an action; every budget the engine has here is far shorter or longer. */
    private static final long WAIT_SECONDS = 30;

    /** What a recovery action keeps when its call carried no input. */
    private static final Object NO_INPUT = "no input";

    /** Workers belong to the test, including calls made through the async facade. */
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    /** Pending stages and calls are retained even when an assertion or watchdog ends a test early. */
    private final List<CompletableFuture<?>> pendingStages = new CopyOnWriteArrayList<>();
    private final List<CompletableFuture<?>> calls = new CopyOnWriteArrayList<>();
    private final List<ForkJoinPool> pools = new CopyOnWriteArrayList<>();

    /** Bounds the entire test independently of engine deadlines and releases every owned resource in finally. */
    private void bounded(Executable test) {
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(WAIT_SECONDS), test);
        } finally {
            for (CompletableFuture<?> stage : pendingStages) {
                stage.complete(null);
            }
            for (CompletableFuture<?> call : calls) {
                call.cancel(true);
            }
            for (ForkJoinPool pool : pools) {
                pool.shutdownNow();
            }
            workers.shutdownNow();
        }
    }

    /** A stage that cleanup can release even when the test never reaches its intended completion. */
    private <T> CompletableFuture<T> pending() {
        CompletableFuture<T> stage = new CompletableFuture<>();
        pendingStages.add(stage);
        return stage;
    }

    /**
     * A producer stage that acknowledges real handle registration only after super.handle returns. The signal
     * records whether the producer stayed incomplete across registration, so an entered action cannot stand in
     * for the engine actually observing a pending stage.
     */
    private static final class RegisteredStage<T> extends CompletableFuture<T> {
        private final CompletableFuture<Boolean> registered = new CompletableFuture<>();

        /** Registers the real callback before reporting whether its producer remained incomplete. */
        @Override
        public <U> CompletableFuture<U> handle(BiFunction<? super T, Throwable, ? extends U> action) {
            boolean incompleteBefore = !isDone();
            CompletableFuture<U> dependent = super.handle(action);
            registered.complete(incompleteBefore && !isDone());
            return dependent;
        }

        /** Bounds the acknowledgement wait and proves the producer was pending at registration and remains so. */
        private void awaitPendingRegistration() throws Exception {
            assertEquals(Boolean.TRUE, registered.get(WAIT_SECONDS, TimeUnit.SECONDS),
                    "the engine must register handle against an incomplete producer");
            assertFalse(isDone(), "the producer must remain pending until the test completes it");
        }
    }

    /** Retains both the producer and its acknowledgement for the common finally cleanup. */
    private <T> RegisteredStage<T> registeredStage() {
        RegisteredStage<T> stage = new RegisteredStage<>();
        pendingStages.add(stage);
        pendingStages.add(stage.registered);
        return stage;
    }

    /** Starts and retains the facade's future; executor ownership stays with the test. */
    private CompletableFuture<Outcome<Ticket, TicketState, TicketChange>> start(
            StateMachine<Ticket, TicketState, TicketChange> machine, Ticket ticket, Object input) {
        CompletableFuture<Outcome<Ticket, TicketState, TicketChange>> call =
                machine.async().execute(ticket, TicketChange.PUBLISH, input);
        calls.add(call);
        return call;
    }

    /** Starts and retains the facade's future of a call without input; executor ownership stays with the test. */
    private CompletableFuture<Outcome<Ticket, TicketState, TicketChange>> start(
            StateMachine<Ticket, TicketState, TicketChange> machine, Ticket ticket) {
        CompletableFuture<Outcome<Ticket, TicketState, TicketChange>> call =
                machine.async().execute(ticket, TicketChange.PUBLISH);
        calls.add(call);
        return call;
    }

    /** The fixture's five-second root budget with test-owned async workers and counted state writes. */
    private StateMachine<Ticket, TicketState, TicketChange> machine(
            GraphDefinition<Ticket, TicketState, TicketChange> graph) {
        return machine(graph, Duration.ofSeconds(5));
    }

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

    /** The input each recovery action received, by ticket id and recovery identifier, as in "a SCHEDULE_RETRY". */
    private final Map<String, Object> received = new ConcurrentHashMap<>();

    /**
     * The publication graph of {@link RecoveryFixtures#graph} with an action on every recovery edge: PUBLISH is
     * configured by {@code publish} and SCHEDULE_RETRY by {@code retry}, while RECORD_REJECTION and
     * MARK_INTEGRATION_FAILED run a {@link #recording} action.
     */
    private GraphDefinition<Ticket, TicketState, TicketChange> tickets(
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
                        .action("record rejection", recording("record rejection")).endTransition()
                .transition(TicketChange.MARK_INTEGRATION_FAILED, TicketState.READY, TicketState.INTEGRATION_FAILED)
                        .action("mark integration failed", recording("mark integration failed")).endTransition()
                .transition(TicketChange.RETRY, TicketState.RETRY_PENDING, TicketState.READY).endTransition()
                .transition(TicketChange.CLOSE, TicketState.REJECTED_BY_PROVIDER, TicketState.CLOSED).endTransition()
                .transition(TicketChange.CLOSE, TicketState.INTEGRATION_FAILED, TicketState.CLOSED).endTransition()
                .build();
    }

    /** {@link #tickets(UnaryOperator, UnaryOperator)} with a {@link #recording} action on SCHEDULE_RETRY. */
    private GraphDefinition<Ticket, TicketState, TicketChange> tickets(
            UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>> publish) {
        return tickets(publish, (TransitionBuilder<Ticket, TicketState, TicketChange> retry) ->
                retry.action("schedule retry", recording("schedule retry")));
    }

    /**
     * A synchronous recovery action that logs {@code name} on the ticket and keeps in {@link #received} the input it
     * got, or {@link #NO_INPUT}.
     */
    private TransitionAction<Ticket, TicketState, TicketChange> recording(String name) {
        return (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> {
            ctx.entity().calls.add(name);
            received.put(ctx.entity().id + " " + ctx.transition(), ctx.input(Object.class).orElse(NO_INPUT));
            return ActionOutcome.ok();
        };
    }

    /**
     * PUBLISH with the rules of the example graph and a synchronous action that logs "publish" and returns a failure
     * of the kind Unavailable, whose rule selects SCHEDULE_RETRY.
     */
    private static UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>> unavailable() {
        return (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> RecoveryFixtures.exampleRules(
                publish.action("publish", (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> {
                    ctx.entity().calls.add("publish");
                    return ActionOutcome.failed(PublicationFailures.Unavailable.class, "provider busy");
                }));
    }

    /** A machine like {@link RecoveryFixtures#machine}, whose roots wait {@code asyncTimeout} in all. */
    private StateMachine<Ticket, TicketState, TicketChange> machine(
            GraphDefinition<Ticket, TicketState, TicketChange> graph, Duration asyncTimeout) {
        return StateMachines.from(graph)
                .state((Ticket ticket) -> ticket.state, (Ticket ticket, TicketState state) -> {
                    ticket.state = state;
                    ticket.stateWrites++;
                })
                .identity((Ticket ticket) -> ticket.id)
                .asyncExecutor(workers)
                .asyncTimeout(asyncTimeout)
                .build();
    }

    /** Publishes {@code ticket} with {@code input} through the async facade of {@code machine} and waits for it. */
    private Outcome<Ticket, TicketState, TicketChange> viaFacade(
            StateMachine<Ticket, TicketState, TicketChange> machine, Ticket ticket, Object input) throws Exception {
        return start(machine, ticket, input).get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    /** Publishes {@code ticket} without input through the async facade of {@code machine} and waits for it. */
    private Outcome<Ticket, TicketState, TicketChange> viaFacade(
            StateMachine<Ticket, TicketState, TicketChange> machine, Ticket ticket) throws Exception {
        return start(machine, ticket).get(WAIT_SECONDS, TimeUnit.SECONDS);
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

    /**
     * How a routed publication ended: its code, the recovery it took and where that moved the ticket, its failure
     * kind and whether its cause is {@code thrown}; then the ticket's state and how many times it was written.
     */
    private static String routed(Ticket ticket, Throwable thrown, Outcome<Ticket, TicketState, TicketChange> outcome) {
        String state = "; ticket " + ticket.state + " after " + ticket.stateWrites + " write(s)";
        Optional<Outcome.Failed<Ticket, TicketState, TicketChange>> failed = outcome.asFailed();
        if (failed.isEmpty()) {
            return outcome.code() + state;
        }
        String cause = failed.get().cause()
                .map((Throwable value) -> value == thrown ? "the thrown exception" : "cause " + value)
                .orElse("no cause");
        return outcome.code() + " " + failed.get().failureTransition() + " to " + failed.get().movedTo() + ", kind "
                + failed.get().failureKind().map(Class::getSimpleName).orElse("none") + ", " + cause + state;
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

    /** Each hop the outcome committed, as {@code FROM -ID-> TO KIND action at depth N}. */
    private static List<String> hops(Outcome<Ticket, TicketState, TicketChange> outcome) {
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

    /** A pool of one worker that may not start a spare one: a wait on that worker is refused. */
    private ForkJoinPool capped() {
        ForkJoinPool pool = new ForkJoinPool(1, ForkJoinPool.defaultForkJoinWorkerThreadFactory, null, false, 0, 1, 1,
                null, 60, TimeUnit.SECONDS);
        pools.add(pool);
        return pool;
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
            List<ReturnedCase> rows = List.of(
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
            for (ReturnedCase row : rows) {
                for (String path : List.of("sync facade", "completed stage", "pending stage")) {
                    String scenario = row.name() + ", " + path;
                    Ticket ticket = new Ticket(scenario);
                    Request input = new Request(scenario);
                    ActionOutcome result = row.kind() == null ? ActionOutcome.failed("provider refused")
                            : ActionOutcome.failed(row.kind(), "provider refused");
                    RegisteredStage<ActionOutcome> stage = registeredStage();
                    UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>> configure =
                            (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> {
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
                                configured = configured.recover(PublicationFailures.Rejected.class,
                                        TicketChange.RECORD_REJECTION);
                                return row.fallback() ? configured.onFailure(TicketChange.MARK_INTEGRATION_FAILED)
                                        : configured;
                            };
                    CompletableFuture<Outcome<Ticket, TicketState, TicketChange>> call =
                            start(machine(tickets(configure)), ticket, input);
                    if (path.equals("pending stage")) {
                        stage.awaitPendingRegistration();
                        stage.complete(result);
                    }
                    if (row.route() == null) {
                        ExecutionException transport = assertThrows(ExecutionException.class,
                                () -> call.get(WAIT_SECONDS, TimeUnit.SECONDS), scenario);
                        UnhandledFailureException error = assertInstanceOf(UnhandledFailureException.class,
                                transport.getCause(), scenario);
                        assertEquals("provider refused", error.reason(), scenario);
                        assertSame(null, error.getCause(), scenario);
                        assertEquals(List.of("publish FAILED"), journal(error), scenario);
                        assertEquals(List.of("publish"), ticket.calls, scenario);
                        assertEquals(0, ticket.stateWrites, scenario);
                    } else {
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
                    assertEquals(row.state(), ticket.state, scenario);
                }
            }
        });
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
        });
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
