package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.ARCHIVE;
import static io.github.graphmaquina.fixtures.DocTransition.PUBLISH;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static io.github.graphmaquina.fixtures.Keys.ITEMS;
import static io.github.graphmaquina.fixtures.Keys.ORDERS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.AsyncTransitionAction;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.error.StaleContextException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import io.github.graphmaquina.fixtures.GuardProbe;
import io.github.graphmaquina.fixtures.Item;
import io.github.graphmaquina.fixtures.ItemStatus;
import io.github.graphmaquina.fixtures.ItemTransition;
import io.github.graphmaquina.fixtures.Order;
import io.github.graphmaquina.fixtures.OrderGraphs;
import io.github.graphmaquina.fixtures.OrderStatus;
import io.github.graphmaquina.fixtures.OrderTransition;
import io.github.graphmaquina.fixtures.Probe;
import java.io.IOException;
import java.time.Duration;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

/**
 * Asynchronous guards and actions on the order and doc fixtures, and the async facade: how a stage is awaited under
 * the asyncTimeout and under an edge timeout, how a failed, cancelled or never-completing stage ends, what an
 * interrupt of the waiting thread does, what a context does once its stage runs on another thread or the call is
 * over, and what the facade does once the registry is closed.
 */
class AsyncTest {

    /** An asynchronous action whose {@code behaviour} defaults to a stage already completed with ok. */
    static final class AsyncProbe implements AsyncTransitionAction<Order, OrderStatus, OrderTransition> {
        Function<TransitionContext<Order, OrderStatus, OrderTransition>, CompletionStage<ActionOutcome>> behaviour =
                (TransitionContext<Order, OrderStatus, OrderTransition> ctx) ->
                        CompletableFuture.completedFuture(ActionOutcome.ok());

        @Override
        public CompletionStage<ActionOutcome> process(TransitionContext<Order, OrderStatus, OrderTransition> ctx) {
            return behaviour.apply(ctx);
        }
    }

    final AsyncProbe chargeCard = new AsyncProbe();
    final Probe<Order, OrderStatus, OrderTransition> shipOrder = new Probe<>();
    final Probe<Order, OrderStatus, OrderTransition> cancelOrder = new Probe<>();
    final GuardProbe<Order, OrderStatus, OrderTransition> retryBudget = new GuardProbe<>();
    final GuardProbe<Order, OrderStatus, OrderTransition> allPacked = new GuardProbe<>();
    final Probe<Item, ItemStatus, ItemTransition> packItem = new Probe<>();
    final ExecutorService virtual = Executors.newVirtualThreadPerTaskExecutor();

    private MachineRegistry registry(Duration asyncTimeout) {
        return MachineRegistry.builder()
                .add(StateMachines.from(OrderGraphs.order(chargeCard, shipOrder, cancelOrder, retryBudget, allPacked))
                        .state(Order::status, Order::setStatus).identity(Order::id).asyncTimeout(asyncTimeout))
                .add(StateMachines.from(OrderGraphs.item(packItem))
                        .state(Item::status, Item::setStatus).identity(Item::id))
                .build();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void asyncActionCompletesLaterAndApplies() {
        chargeCard.behaviour = (TransitionContext<Order, OrderStatus, OrderTransition> ctx) ->
                CompletableFuture.supplyAsync(() -> {
                    sleep(50);
                    return ActionOutcome.ok();
                }, virtual);
        Order order = new Order("o1", OrderStatus.NEW);
        Outcome<Order, OrderStatus, OrderTransition> outcome =
                registry(Duration.ofSeconds(5)).of(ORDERS).execute(order, OrderTransition.CHARGE);
        assertInstanceOf(Outcome.Applied.class, outcome);
        assertEquals(OrderStatus.PAID, order.status());
    }

    @Test
    void cascadeBeforeReturningTheStageUnderASingleCarrier() {
        chargeCard.behaviour = (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> {
            ctx.cascade(ITEMS, ctx.entity().items().get(0), ItemTransition.START_PACKING);
            return CompletableFuture.supplyAsync(() -> {
                sleep(50);
                return ActionOutcome.ok();
            }, virtual);
        };
        Order order = new Order("o1", OrderStatus.NEW);
        Item item = new Item("i1", ItemStatus.PENDING, Optional.of(order));
        StateMachine<Order, OrderStatus, OrderTransition> orders = registry(Duration.ofSeconds(5)).of(ORDERS);
        Outcome<Order, OrderStatus, OrderTransition> outcome = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> orders.async().execute(order, OrderTransition.CHARGE).join());
        assertInstanceOf(Outcome.Applied.class, outcome);
        assertEquals(OrderStatus.PAID, order.status());
        assertEquals(ItemStatus.PACKING, item.status());
    }

    @Test
    void stageThatNeverCompletesAbortsAndIsLeftAlive() {
        CompletableFuture<ActionOutcome> never = new CompletableFuture<>();
        chargeCard.behaviour = (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> never;
        Order order = new Order("o1", OrderStatus.NEW);
        StateMachine<Order, OrderStatus, OrderTransition> orders = registry(Duration.ofMillis(300)).of(ORDERS);
        long start = System.nanoTime();
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                () -> orders.execute(order, OrderTransition.CHARGE));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
        assertTrue(elapsedMillis < 3_000, "took " + elapsedMillis + " ms");
        assertInstanceOf(TimeoutException.class, error.getCause());
        assertEquals(OrderStatus.NEW, order.status());
        assertFalse(never.isDone());
        CompletableFuture<String> branch = never.thenApply((ActionOutcome outcome) -> "still alive");
        never.complete(ActionOutcome.ok());
        assertEquals("still alive", branch.join());
    }

    @Test
    void nestedWrappersAreUnwrappedBeforeFailsOn() {
        chargeCard.behaviour = (TransitionContext<Order, OrderStatus, OrderTransition> ctx) ->
                CompletableFuture.failedFuture(
                        new CompletionException(new ExecutionException(new IOException("gateway down"))));
        Order order = new Order("o1", OrderStatus.NEW);
        Outcome<Order, OrderStatus, OrderTransition> outcome =
                registry(Duration.ofSeconds(5)).of(ORDERS).execute(order, OrderTransition.CHARGE);
        assertInstanceOf(Outcome.Failed.class, outcome);
        Outcome.Failed<Order, OrderStatus, OrderTransition> failed =
                (Outcome.Failed<Order, OrderStatus, OrderTransition>) outcome;
        assertInstanceOf(IOException.class, failed.cause().orElseThrow());
        assertEquals(OrderTransition.DECLINE, failed.failureTransition());
        assertEquals(OrderStatus.PAYMENT_DECLINED, order.status());
    }

    @Test
    void aSynchronousThrowBeforeTheStageIsUnwrappedBeforeFailsOn() {
        chargeCard.behaviour = (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> {
            throw new CompletionException(new IOException("down"));
        };
        Order order = new Order("o1", OrderStatus.NEW);
        Outcome<Order, OrderStatus, OrderTransition> outcome =
                registry(Duration.ofSeconds(5)).of(ORDERS).execute(order, OrderTransition.CHARGE);
        assertInstanceOf(Outcome.Failed.class, outcome);
        Outcome.Failed<Order, OrderStatus, OrderTransition> failed =
                (Outcome.Failed<Order, OrderStatus, OrderTransition>) outcome;
        assertEquals(OrderStatus.PAYMENT_DECLINED, failed.movedTo());
        assertInstanceOf(IOException.class, failed.cause().orElseThrow());
        assertEquals(OrderStatus.PAYMENT_DECLINED, order.status());
    }

    @Test
    void cancelledStageIsATechnicalFailure() {
        chargeCard.behaviour = (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> {
            CompletableFuture<ActionOutcome> cancelled = new CompletableFuture<>();
            cancelled.cancel(true);
            return cancelled;
        };
        Order order = new Order("o1", OrderStatus.NEW);
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                () -> registry(Duration.ofSeconds(5)).of(ORDERS).execute(order, OrderTransition.CHARGE));
        assertInstanceOf(CancellationException.class, error.getCause());
        assertEquals(OrderStatus.NEW, order.status());
    }

    @Test
    void usingTheContextFromInsideTheStageIsRejected() {
        chargeCard.behaviour = (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> CompletableFuture
                .supplyAsync(ActionOutcome::ok)
                .thenApplyAsync((ActionOutcome outcome) -> {
                    ctx.peer(ITEMS);
                    return outcome;
                });
        Order order = new Order("o1", OrderStatus.NEW);
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                () -> registry(Duration.ofSeconds(5)).of(ORDERS).execute(order, OrderTransition.CHARGE));
        assertInstanceOf(WrongThreadException.class, error.getCause());
    }

    @Test
    void aLaggingActionFindsAStaleContext() {
        AtomicReference<TransitionContext<Order, OrderStatus, OrderTransition>> captured = new AtomicReference<>();
        chargeCard.behaviour = (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> {
            captured.set(ctx);
            return new CompletableFuture<>();
        };
        Order order = new Order("o1", OrderStatus.NEW);
        assertThrows(TransitionAbortedException.class,
                () -> registry(Duration.ofMillis(200)).of(ORDERS).execute(order, OrderTransition.CHARGE));
        assertThrows(StaleContextException.class, () -> captured.get().peer(ITEMS));
    }

    @Test
    void asyncFacadeNeverRunsOnTheCallerThread() {
        AtomicReference<Thread> seen = new AtomicReference<>();
        chargeCard.behaviour = (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> {
            seen.set(Thread.currentThread());
            return CompletableFuture.completedFuture(ActionOutcome.ok());
        };
        Order order = new Order("o1", OrderStatus.NEW);
        Outcome<Order, OrderStatus, OrderTransition> outcome =
                registry(Duration.ofSeconds(5)).of(ORDERS).async().execute(order, OrderTransition.CHARGE).join();
        assertInstanceOf(Outcome.Applied.class, outcome);
        assertNotSame(Thread.currentThread(), seen.get());
        assertTrue(seen.get().isVirtual());
    }

    @Test
    void aClosedRegistryFailsFuturesInsteadOfThrowing() {
        MachineRegistry closed = registry(Duration.ofSeconds(5));
        StateMachine<Order, OrderStatus, OrderTransition> orders = closed.of(ORDERS);
        closed.close();
        CompletableFuture<Outcome<Order, OrderStatus, OrderTransition>> future =
                orders.async().execute(new Order("o1", OrderStatus.NEW), OrderTransition.CHARGE);
        assertTrue(future.isCompletedExceptionally());
        CompletionException error = assertThrows(CompletionException.class, future::join);
        assertInstanceOf(RejectedExecutionException.class, error.getCause());
    }

    /**
     * One interrupt of the thread that closes a registry reaches the waiting calls of every machine whose default
     * executor that close() has yet to close, not only the one it waits for: an executor closed on an interrupted
     * thread interrupts its threads, then sets the interrupt flag again once it terminates, so the close of each later
     * machine does the same. Here the interrupt arrives while close() waits for the order machine's call: that call
     * and the doc machine's both abort as interrupted while waiting, and close() returns with the thread interrupted.
     */
    @Test
    void anInterruptedCloseInterruptsTheWaitingCallsOfEveryLaterMachine() throws Exception {
        CompletableFuture<ActionOutcome> never = new CompletableFuture<>();
        CountDownLatch entered = new CountDownLatch(2);
        GraphDefinition<Order, OrderStatus, OrderTransition> orders = ordersBlockedOnCharge(never, entered);
        GraphDefinition<Doc, DocStatus, DocTransition> docs = docsBlockedOnReview(never, entered);
        MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(orders).state(Order::status, Order::setStatus).identity(Order::id)
                        .asyncTimeout(Duration.ofHours(1)))
                .add(StateMachines.from(docs).state(Doc::status, Doc::setStatus).identity(Doc::id)
                        .asyncTimeout(Duration.ofHours(1)))
                .build();
        AtomicReference<Boolean> interruptedAfterClose = new AtomicReference<>();
        Thread closer = new Thread(() -> {
            registry.close();
            interruptedAfterClose.set(Thread.interrupted());
        });
        closer.setDaemon(true);
        try {
            List<CompletableFuture<? extends Outcome<?, ?, ?>>> calls = List.of(
                    registry.of(ORDERS).async().execute(new Order("o1", OrderStatus.NEW), OrderTransition.CHARGE),
                    registry.of(DOCS).async().execute(new Doc("d1", DRAFT), SUBMIT));
            assertTrue(entered.await(10, TimeUnit.SECONDS), "the calls did not start");
            closer.start();
            awaitAndAssertCloseWaitingOnTheOrderCall(closer);
            closer.interrupt();
            closer.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(closer.isAlive(), "close() did not return");
            assertEquals(List.of("aborted by InterruptedException", "aborted by InterruptedException"),
                    endsOfTheCalls(calls));
            assertEquals(true, interruptedAfterClose.get());
        } finally {
            never.complete(ActionOutcome.ok());
        }
    }

    private static GraphDefinition<Order, OrderStatus, OrderTransition> ordersBlockedOnCharge(
            CompletableFuture<ActionOutcome> never, CountDownLatch entered) {
        return StateGraph.define(ORDERS)
                .initial(OrderStatus.NEW).terminal(OrderStatus.SHIPPED).terminal(OrderStatus.CANCELLED)
                .terminal(OrderStatus.PAID).terminal(OrderStatus.PAYMENT_DECLINED)
                .statePolicy(OrderStatus.NEW, "x")
                .transition(OrderTransition.CHARGE, OrderStatus.NEW, OrderStatus.PAID).asyncAction("Charge",
                        (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> {
                            entered.countDown();
                            return never;
                        }).endTransition()
                .transition(OrderTransition.CANCEL, OrderStatus.NEW, OrderStatus.CANCELLED).endTransition()
                .transition(OrderTransition.SHIP, OrderStatus.NEW, OrderStatus.SHIPPED).endTransition()
                .transition(OrderTransition.DECLINE, OrderStatus.NEW, OrderStatus.PAYMENT_DECLINED).endTransition()
                .build();
    }

    private static GraphDefinition<Doc, DocStatus, DocTransition> docsBlockedOnReview(
            CompletableFuture<ActionOutcome> never, CountDownLatch entered) {
        return StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                .transition(SUBMIT, DRAFT, REVIEW).asyncAction("Review",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            entered.countDown();
                            return never;
                        }).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
    }

    private static void awaitAndAssertCloseWaitingOnTheOrderCall(Thread closer) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (closer.getState() != Thread.State.TIMED_WAITING && System.nanoTime() - deadline < 0) {
            Thread.sleep(1);
        }
        assertEquals(Thread.State.TIMED_WAITING, closer.getState(), "close() did not wait for the order call");
    }

    /**
     * How each of {@code calls} ended, in order: the status of a call that completed, or its abort as
     * {@link #describeAbort} describes it, the first call waiting for Charge and the second for Review; a call that
     * failed with anything but an abort fails the test.
     */
    private static List<String> endsOfTheCalls(
            List<CompletableFuture<? extends Outcome<?, ?, ?>>> calls) throws Exception {
        List<String> actions = List.of("Charge", "Review");
        List<String> ends = new ArrayList<>();
        for (int call = 0; call < calls.size(); call++) {
            try {
                ends.add(calls.get(call).get(10, TimeUnit.SECONDS).status().name());
            } catch (ExecutionException e) {
                TransitionAbortedException aborted = assertInstanceOf(TransitionAbortedException.class, e.getCause());
                ends.add(describeAbort(aborted, actions.get(call)));
            }
        }
        return ends;
    }

    /** The abort's cause, with its message only when it is not the interrupt of the wait for {@code action}. */
    private static String describeAbort(TransitionAbortedException aborted, String action) {
        return "aborted by " + aborted.getCause().getClass().getSimpleName()
                + (aborted.getMessage().contains("interrupted while waiting for the action " + action)
                        ? "" : ": " + aborted.getMessage());
    }

    @Test
    void asyncGuardIsAwaited() {
        GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                .transition(SUBMIT, DRAFT, REVIEW)
                    .asyncGuard("Slow", (GuardContext<Doc, DocStatus, DocTransition> ctx) ->
                            CompletableFuture.supplyAsync(() -> GuardResult.reject("not yet"), virtual))
                    .endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
        StateMachine<Doc, DocStatus, DocTransition> docs = StateMachines.from(graph)
                .state(Doc::status, Doc::setStatus).asyncTimeout(Duration.ofSeconds(2)).build();
        Outcome<Doc, DocStatus, DocTransition> outcome = docs.execute(new Doc("1", DRAFT), SUBMIT);
        assertInstanceOf(Outcome.Rejected.class, outcome);
        Outcome.Rejected<Doc, DocStatus, DocTransition> rejected =
                (Outcome.Rejected<Doc, DocStatus, DocTransition>) outcome;
        assertEquals(Optional.of("Slow"), rejected.guard());
    }

    @Test
    void anEdgeTimeoutOnlyShortensTheDeadline() {
        GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                .transition(SUBMIT, DRAFT, REVIEW)
                    .asyncAction("Slow",
                            (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> new CompletableFuture<>())
                    .timeout(Duration.ofMillis(200)).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
        StateMachine<Doc, DocStatus, DocTransition> docs = StateMachines.from(graph)
                .state(Doc::status, Doc::setStatus).asyncTimeout(Duration.ofSeconds(30)).build();
        long start = System.nanoTime();
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                () -> docs.execute(new Doc("1", DRAFT), SUBMIT));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
        assertTrue(elapsedMillis < 5_000, "took " + elapsedMillis + " ms");
        assertInstanceOf(TimeoutException.class, error.getCause());
    }

    /**
     * An interrupt of the waiting thread ends the wait. An action that interrupts its own thread and returns a stage
     * that is not complete aborts the call as interrupted while waiting, even when its edge's budget is already
     * spent, and the thread is still interrupted afterwards. A stage that is already complete is taken as it is, and
     * the thread stays interrupted.
     */
    @Test
    void anInterruptedWaitAbortsUnlessTheStageIsAlreadyComplete() throws Exception {
        List<String> ends = new ArrayList<>();
        for (String stage : List.of("pending", "pending with a spent budget", "complete")) {
            TransitionBuilder<Doc, DocStatus, DocTransition> submitting = StateGraph.define(DOCS)
                    .initial(DRAFT).terminal(ARCHIVED)
                    .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                    .transition(SUBMIT, DRAFT, REVIEW).asyncAction("Interrupting",
                            (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                                Thread.currentThread().interrupt();
                                return stage.equals("complete") ? CompletableFuture.completedFuture(ActionOutcome.ok())
                                        : new CompletableFuture<ActionOutcome>();
                            });
            if (stage.endsWith("spent budget")) {
                submitting = submitting.timeout(Duration.ofNanos(1));
            }
            GraphDefinition<Doc, DocStatus, DocTransition> graph = submitting.endTransition()
                    .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                    .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                    .build();
            StateMachine<Doc, DocStatus, DocTransition> docs = StateMachines.from(graph)
                    .state(Doc::status, Doc::setStatus).asyncTimeout(Duration.ofSeconds(5)).build();
            Doc doc = new Doc("1", DRAFT);
            try (ExecutorService thread = Executors.newSingleThreadExecutor()) {
                String end = thread.submit(() -> {
                    String how;
                    try {
                        how = docs.execute(doc, SUBMIT).status().name();
                    } catch (TransitionAbortedException e) {
                        how = describeAbort(e, "Interrupting");
                    }
                    return how + ", interrupted " + Thread.interrupted();
                }).get(10, TimeUnit.SECONDS);
                ends.add(stage + ": " + end + ", " + doc.status());
            }
        }
        assertEquals(List.of(
                "pending: aborted by InterruptedException, interrupted true, DRAFT",
                "pending with a spent budget: aborted by InterruptedException, interrupted true, DRAFT",
                "complete: APPLIED, interrupted true, REVIEW"), ends);
    }

    @Test
    void timeoutRetainsOnlyDataAccessAndNextCallGetsNoInput() {
        AtomicReference<TransitionContext<Order, OrderStatus, OrderTransition>> captured = new AtomicReference<>();
        Object value = new Object();
        chargeCard.behaviour = (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> {
            captured.set(ctx);
            return new CompletableFuture<>();
        };
        try (MachineRegistry r = registry(Duration.ofMillis(200))) {
            assertThrows(TransitionAbortedException.class,
                    () -> r.of(ORDERS).execute(new Order("a", OrderStatus.NEW), OrderTransition.CHARGE, value));
            assertSame(value, captured.get().input().orElseThrow());
            assertThrows(StaleContextException.class, () -> captured.get().peer(ITEMS));
            chargeCard.behaviour = (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> {
                assertTrue(ctx.input().isEmpty());
                return CompletableFuture.completedFuture(ActionOutcome.ok());
            };
            r.of(ORDERS).execute(new Order("b", OrderStatus.NEW), OrderTransition.CHARGE);
        }
    }

    @Test
    void closedAsyncWithInputReturnsFailedFuture() {
        MachineRegistry r = registry(Duration.ofSeconds(5));
        StateMachine<Order, OrderStatus, OrderTransition> m = r.of(ORDERS);
        r.close();
        CompletableFuture<Outcome<Order, OrderStatus, OrderTransition>> future =
                m.async().execute(new Order("a", OrderStatus.NEW), OrderTransition.CHARGE, new Object());
        assertInstanceOf(RejectedExecutionException.class,
                assertThrows(CompletionException.class, future::join).getCause());
    }

    /** A future whose observable registration must be preserved without querying overridable completion readers. */
    private static final class ObservedFuture<T> extends CompletableFuture<T> {
        private int registrations;
        private Optional<RuntimeException> registrationFailure = Optional.empty();

        @Override
        public <U> CompletableFuture<U> handle(BiFunction<? super T, Throwable, ? extends U> callback) {
            registrations++;
            if (registrationFailure.isPresent()) {
                throw registrationFailure.orElseThrow();
            }
            return super.handle(callback);
        }

        @Override
        public Future.State state() { throw new AssertionError("subclass state must not be read"); }

        @Override
        public T resultNow() { throw new AssertionError("subclass resultNow must not be read"); }

        @Override
        public CompletableFuture<T> toCompletableFuture() {
            throw new AssertionError("subclass conversion must not be requested");
        }
    }

    /** An arbitrary stage exposing handle while refusing conversion; only its own stage API is intercepted. */
    private static final class OpaqueStage<T> implements InvocationHandler {
        private final CompletableFuture<T> delegate;
        private int registrations;

        private OpaqueStage(T value) { delegate = CompletableFuture.completedFuture(value); }

        @SuppressWarnings("unchecked")
        private CompletionStage<T> stage() {
            return (CompletionStage<T>) Proxy.newProxyInstance(CompletionStage.class.getClassLoader(),
                    new Class<?>[] {CompletionStage.class}, this);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object invoke(Object proxy, Method method, Object[] arguments) {
            if (method.getName().equals("toCompletableFuture")) {
                throw new UnsupportedOperationException("conversion is unsupported");
            }
            if (method.getName().equals("handle")) {
                registrations++;
                return delegate.handle((BiFunction<? super T, Throwable, ?>) arguments[0]);
            }
            throw new AssertionError("unexpected stage method " + method.getName());
        }
    }

    /** A doc machine with an async guard and action, so both callback boundaries are exercised end to end. */
    private static StateMachine<Doc, DocStatus, DocTransition> completedMachine(
            Function<GuardContext<Doc, DocStatus, DocTransition>, CompletionStage<GuardResult>> guard,
            CompletionStage<ActionOutcome> action, Duration budget) {
        GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                .transition(SUBMIT, DRAFT, REVIEW)
                    .asyncGuard("CompletedGuard", guard::apply)
                    .asyncAction("CompletedAction",
                            (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> action).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition().build();
        return StateMachines.from(graph).state(Doc::status, Doc::setStatus).identity(Doc::id)
                .asyncTimeout(budget).build();
    }

    @Test
    void completedPlainCallbacksApplyOrRefuseTheirNullResults() {
        for (String scenario : List.of("success", "null action", "null guard")) {
            CompletionStage<GuardResult> guard = CompletableFuture.completedFuture(
                    scenario.equals("null guard") ? null : GuardResult.pass());
            CompletionStage<ActionOutcome> action = CompletableFuture.completedFuture(
                    scenario.equals("null action") ? null : ActionOutcome.ok());
            StateMachine<Doc, DocStatus, DocTransition> docs = completedMachine(
                    (GuardContext<Doc, DocStatus, DocTransition> ctx) -> guard, action, Duration.ofSeconds(5));
            Doc doc = new Doc("completed", DRAFT);
            if (scenario.equals("success")) {
                assertInstanceOf(Outcome.Applied.class, docs.execute(doc, SUBMIT));
                assertEquals(REVIEW, doc.status());
            } else {
                assertNullResultAbortsTheCall(docs, doc, scenario.equals("null guard"));
            }
        }
    }

    private static void assertNullResultAbortsTheCall(StateMachine<Doc, DocStatus, DocTransition> docs, Doc doc,
            boolean isGuard) {
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                () -> docs.execute(doc, SUBMIT));
        assertEquals(isGuard ? CallPhase.GUARD : CallPhase.ACTION, error.phase());
        assertEquals("transition aborted in Doc/DocStatus DRAFT -SUBMIT-> REVIEW: the "
                + (isGuard ? "guard CompletedGuard" : "action CompletedAction")
                + " returned null [route Doc/DocStatus#completed]", error.getMessage());
        assertEquals(Optional.empty(), Optional.ofNullable(error.getCause()));
        assertEquals(DRAFT, doc.status());
    }

    @Test
    void completedSubclassCallbacksKeepTheirHandleSideEffects() {
        ObservedFuture<GuardResult> guard = new ObservedFuture<>();
        ObservedFuture<ActionOutcome> action = new ObservedFuture<>();
        guard.complete(GuardResult.pass());
        action.complete(ActionOutcome.ok());
        StateMachine<Doc, DocStatus, DocTransition> docs = completedMachine(
                (GuardContext<Doc, DocStatus, DocTransition> ctx) -> guard, action, Duration.ofSeconds(5));
        Doc doc = new Doc("subclass", DRAFT);
        assertInstanceOf(Outcome.Applied.class, docs.execute(doc, SUBMIT));
        assertEquals(REVIEW, doc.status());
        assertEquals(1, guard.registrations);
        assertEquals(1, action.registrations);
    }

    @Test
    void minimalAndOpaqueStagesPreserveHandleWithoutConversion() {
        OpaqueStage<GuardResult> opaqueGuard = new OpaqueStage<>(GuardResult.pass());
        OpaqueStage<ActionOutcome> opaqueAction = new OpaqueStage<>(ActionOutcome.ok());
        for (String scenario : List.of("minimal", "opaque")) {
            CompletionStage<GuardResult> guard = scenario.equals("minimal")
                    ? CompletableFuture.completedFuture(GuardResult.pass()).minimalCompletionStage()
                    : opaqueGuard.stage();
            CompletionStage<ActionOutcome> action = scenario.equals("minimal")
                    ? CompletableFuture.completedFuture(ActionOutcome.ok()).minimalCompletionStage()
                    : opaqueAction.stage();
            StateMachine<Doc, DocStatus, DocTransition> docs = completedMachine(
                    (GuardContext<Doc, DocStatus, DocTransition> ctx) -> guard, action, Duration.ofSeconds(5));
            Doc doc = new Doc(scenario, DRAFT);
            assertInstanceOf(Outcome.Applied.class, docs.execute(doc, SUBMIT));
            assertEquals(REVIEW, doc.status());
        }
        assertEquals(1, opaqueGuard.registrations);
        assertEquals(1, opaqueAction.registrations);
    }

    @Test
    void initiallyCompletedGuardsWinOverInterruptAndSpentRootBudget() throws Exception {
        for (boolean interrupted : List.of(false, true)) {
            StateMachine<Doc, DocStatus, DocTransition> docs = completedMachine(
                    (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                        sleep(10);
                        if (interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        return CompletableFuture.completedFuture(GuardResult.pass());
                    }, CompletableFuture.completedFuture(ActionOutcome.ok()), Duration.ofNanos(1));
            Doc doc = new Doc("precedence", DRAFT);
            try (ExecutorService thread = Executors.newSingleThreadExecutor()) {
                Future<Boolean> result = thread.submit(() -> {
                    try {
                        assertInstanceOf(Outcome.Applied.class, docs.execute(doc, SUBMIT));
                        return Thread.currentThread().isInterrupted();
                    } finally {
                        Thread.interrupted();
                    }
                });
                assertEquals(interrupted, result.get(10, TimeUnit.SECONDS));
            }
            assertEquals(REVIEW, doc.status());
        }
    }

    @Test
    void concurrentPlainFutureObtrusionHasOnlyLegalCompletionOutcomes() throws Exception {
        try (ExecutorService obtruder = Executors.newSingleThreadExecutor()) {
            for (int round = 0; round < 64; round++) {
                CompletableFuture<ActionOutcome> stage = CompletableFuture.completedFuture(ActionOutcome.ok());
                IllegalArgumentException cause = new IllegalArgumentException("obtruded " + round);
                CountDownLatch ready = new CountDownLatch(1);
                Future<?> mutation = obtruder.submit(() -> {
                    assertTrue(ready.await(10, TimeUnit.SECONDS));
                    stage.obtrudeException(cause);
                    return Optional.empty();
                });
                StateMachine<Doc, DocStatus, DocTransition> docs = completedMachine(
                        (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ready.countDown();
                            return CompletableFuture.completedFuture(GuardResult.pass());
                        }, stage, Duration.ofSeconds(5));
                Doc doc = new Doc("race", DRAFT);
                try {
                    assertInstanceOf(Outcome.Applied.class, docs.execute(doc, SUBMIT));
                    assertEquals(REVIEW, doc.status());
                } catch (TransitionAbortedException error) {
                    assertSame(cause, error.getCause());
                    assertEquals(CallPhase.ACTION, error.phase());
                    assertEquals(DRAFT, doc.status());
                } finally {
                    ready.countDown();
                    mutation.get(10, TimeUnit.SECONDS);
                }
            }
        }
    }

    @Test
    void failedCancelledAndRegistrationFailingSubclassesKeepOriginalCauses() {
        ObservedFuture<ActionOutcome> routed = new ObservedFuture<>();
        IOException down = new IOException("subclass gateway down");
        routed.completeExceptionally(down);
        chargeCard.behaviour = (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> routed;
        Order order = new Order("routed subclass", OrderStatus.NEW);
        try (MachineRegistry r = registry(Duration.ofSeconds(5))) {
            Outcome.Failed<Order, OrderStatus, OrderTransition> failed =
                    r.of(ORDERS).execute(order, OrderTransition.CHARGE).asFailed().orElseThrow();
            assertSame(down, failed.cause().orElseThrow());
            assertEquals(OrderTransition.DECLINE, failed.failureTransition());
            assertEquals(OrderStatus.PAYMENT_DECLINED, order.status());
            assertEquals(1, routed.registrations);
        }
        for (String scenario : List.of("failed", "cancelled", "registration")) {
            ObservedFuture<ActionOutcome> stage = new ObservedFuture<>();
            RuntimeException cause = scenario.equals("cancelled") ? new CancellationException("cancelled subclass")
                    : new IllegalArgumentException(scenario);
            if (scenario.equals("registration")) {
                stage.complete(ActionOutcome.ok());
                stage.registrationFailure = Optional.of(cause);
            } else {
                stage.completeExceptionally(cause);
            }
            StateMachine<Doc, DocStatus, DocTransition> docs = completedMachine(
                    (GuardContext<Doc, DocStatus, DocTransition> ctx) ->
                            CompletableFuture.completedFuture(GuardResult.pass()), stage, Duration.ofSeconds(5));
            Doc doc = new Doc(scenario, DRAFT);
            TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                    () -> docs.execute(doc, SUBMIT));
            assertSame(cause, error.getCause());
            assertEquals(CallPhase.ACTION, error.phase());
            assertEquals(DRAFT, doc.status());
            assertEquals(1, stage.registrations);
        }
    }
}
