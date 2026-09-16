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
import io.github.graphmaquina.api.AsyncTransitionAction;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
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
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class AsyncTest {

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
                CompletableFuture.failedFuture(new CompletionException(new ExecutionException(new IOException("gateway down"))));
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
                    .asyncAction("Slow", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> new CompletableFuture<>())
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
        assertInstanceOf(RejectedExecutionException.class, assertThrows(CompletionException.class, future::join).getCause());
    }
}
