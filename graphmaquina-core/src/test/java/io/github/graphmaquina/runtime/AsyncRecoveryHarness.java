package io.github.graphmaquina.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.ActionRecord;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.GraphBuilder;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.fixtures.RecoveryFixtures;
import io.github.graphmaquina.fixtures.RecoveryFixtures.Ticket;
import io.github.graphmaquina.fixtures.RecoveryFixtures.TicketChange;
import io.github.graphmaquina.fixtures.RecoveryFixtures.TicketState;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.function.Executable;

/**
 * What the typed-recovery tests of the asynchronous paths share: the ticket machines and the publication graph they
 * run, and the means to bound every test and to release what it left running. Each test has an independent preemptive
 * watchdog and finally cleanup of pending stages, facade calls and test-owned workers. The tests are in
 * {@link AsyncRecoveryTest}, for the route a failure selects, and in {@link AsyncRecoveryEdgeTest}, for the recovery
 * that follows.
 */
abstract class AsyncRecoveryHarness {

    /** How long a test waits for a call or an action; every budget the engine has here is far shorter or longer. */
    static final long WAIT_SECONDS = 30;

    /** What a recovery action keeps when its call carried no input. */
    private static final Object NO_INPUT = "no input";

    /** Workers belong to the test, including calls made through the async facade. */
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    /** Pending stages and calls are retained even when an assertion or watchdog ends a test early. */
    private final List<CompletableFuture<?>> pendingStages = new CopyOnWriteArrayList<>();
    final List<CompletableFuture<?>> calls = new CopyOnWriteArrayList<>();
    private final List<ForkJoinPool> pools = new CopyOnWriteArrayList<>();

    /** The input each recovery action received, by ticket id and recovery identifier, as in "a SCHEDULE_RETRY". */
    final Map<String, Object> received = new ConcurrentHashMap<>();

    /** Bounds the entire test independently of engine deadlines and releases every owned resource in finally. */
    void bounded(Executable test) {
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
    <T> CompletableFuture<T> pending() {
        CompletableFuture<T> stage = new CompletableFuture<>();
        pendingStages.add(stage);
        return stage;
    }

    /**
     * A producer stage that acknowledges real handle registration only after super.handle returns. The signal
     * records whether the producer stayed incomplete across registration, so an entered action cannot stand in
     * for the engine actually observing a pending stage.
     */
    static final class RegisteredStage<T> extends CompletableFuture<T> {
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
        void awaitPendingRegistration() throws Exception {
            assertEquals(Boolean.TRUE, registered.get(WAIT_SECONDS, TimeUnit.SECONDS),
                    "the engine must register handle against an incomplete producer");
            assertFalse(isDone(), "the producer must remain pending until the test completes it");
        }
    }

    /** Retains both the producer and its acknowledgement for the common finally cleanup. */
    <T> RegisteredStage<T> registeredStage() {
        RegisteredStage<T> stage = new RegisteredStage<>();
        pendingStages.add(stage);
        pendingStages.add(stage.registered);
        return stage;
    }

    /** Starts and retains the facade's future; executor ownership stays with the test. */
    CompletableFuture<Outcome<Ticket, TicketState, TicketChange>> start(
            StateMachine<Ticket, TicketState, TicketChange> machine, Ticket ticket, Object input) {
        CompletableFuture<Outcome<Ticket, TicketState, TicketChange>> call =
                machine.async().execute(ticket, TicketChange.PUBLISH, input);
        calls.add(call);
        return call;
    }

    /** Starts and retains the facade's future of a call without input; executor ownership stays with the test. */
    CompletableFuture<Outcome<Ticket, TicketState, TicketChange>> start(
            StateMachine<Ticket, TicketState, TicketChange> machine, Ticket ticket) {
        CompletableFuture<Outcome<Ticket, TicketState, TicketChange>> call =
                machine.async().execute(ticket, TicketChange.PUBLISH);
        calls.add(call);
        return call;
    }

    /** The fixture's five-second root budget with test-owned async workers and counted state writes. */
    StateMachine<Ticket, TicketState, TicketChange> machine(
            GraphDefinition<Ticket, TicketState, TicketChange> graph) {
        return machine(graph, Duration.ofSeconds(5));
    }

    /**
     * The publication graph of {@link RecoveryFixtures#graph} with an action on every recovery edge: PUBLISH is
     * configured by {@code publish} and SCHEDULE_RETRY by {@code retry}, while RECORD_REJECTION and
     * MARK_INTEGRATION_FAILED run a {@link #recording} action.
     */
    GraphDefinition<Ticket, TicketState, TicketChange> tickets(
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
    GraphDefinition<Ticket, TicketState, TicketChange> tickets(
            UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>> publish) {
        return tickets(publish, (TransitionBuilder<Ticket, TicketState, TicketChange> retry) ->
                retry.action("schedule retry", recording("schedule retry")));
    }

    /**
     * A synchronous recovery action that logs {@code name} on the ticket and keeps in {@link #received} the input it
     * got, or {@link #NO_INPUT}.
     */
    TransitionAction<Ticket, TicketState, TicketChange> recording(String name) {
        return (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> {
            ctx.entity().calls.add(name);
            received.put(ctx.entity().id + " " + ctx.transition(), ctx.input(Object.class).orElse(NO_INPUT));
            return ActionOutcome.ok();
        };
    }

    /** A machine like {@link RecoveryFixtures#machine}, whose roots wait {@code asyncTimeout} in all. */
    StateMachine<Ticket, TicketState, TicketChange> machine(
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
    Outcome<Ticket, TicketState, TicketChange> viaFacade(
            StateMachine<Ticket, TicketState, TicketChange> machine, Ticket ticket, Object input) throws Exception {
        return start(machine, ticket, input).get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    /** Publishes {@code ticket} without input through the async facade of {@code machine} and waits for it. */
    Outcome<Ticket, TicketState, TicketChange> viaFacade(
            StateMachine<Ticket, TicketState, TicketChange> machine, Ticket ticket) throws Exception {
        return start(machine, ticket).get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * How a routed publication ended: its code, the recovery it took and where that moved the ticket, its failure
     * kind and whether its cause is {@code thrown}; then the ticket's state and how many times it was written.
     */
    static String routed(Ticket ticket, Throwable thrown, Outcome<Ticket, TicketState, TicketChange> outcome) {
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

    /** Each action of the abort's journal, as {@code name STATUS}. */
    static List<String> journal(TransitionAbortedException error) {
        return error.journal().stream()
                .map((ActionRecord record) -> record.action() + " " + record.status())
                .toList();
    }

    /** A pool of one worker that may not start a spare one: a wait on that worker is refused. */
    ForkJoinPool capped() {
        ForkJoinPool pool = new ForkJoinPool(1, ForkJoinPool.defaultForkJoinWorkerThreadFactory, null, false, 0, 1, 1,
                null, 60, TimeUnit.SECONDS);
        pools.add(pool);
        return pool;
    }
}
