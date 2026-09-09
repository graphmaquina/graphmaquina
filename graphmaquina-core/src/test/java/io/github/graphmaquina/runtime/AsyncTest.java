package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static io.github.graphmaquina.fixtures.Keys.ITEMS;
import static io.github.graphmaquina.fixtures.Keys.ORDERS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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
import io.github.graphmaquina.fixtures.GuardProbe;
import io.github.graphmaquina.fixtures.Item;
import io.github.graphmaquina.fixtures.ItemStatus;
import io.github.graphmaquina.fixtures.Order;
import io.github.graphmaquina.fixtures.OrderGraphs;
import io.github.graphmaquina.fixtures.OrderStatus;
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

    static final class AsyncProbe implements AsyncTransitionAction<Order, OrderStatus> {
        Function<TransitionContext<Order, OrderStatus>, CompletionStage<ActionOutcome>> behaviour =
                (TransitionContext<Order, OrderStatus> ctx) -> CompletableFuture.completedFuture(ActionOutcome.ok());

        @Override
        public CompletionStage<ActionOutcome> process(TransitionContext<Order, OrderStatus> ctx) {
            return behaviour.apply(ctx);
        }
    }

    final AsyncProbe chargeCard = new AsyncProbe();
    final Probe<Order, OrderStatus> shipOrder = new Probe<>();
    final Probe<Order, OrderStatus> cancelOrder = new Probe<>();
    final GuardProbe<Order, OrderStatus> retryBudget = new GuardProbe<>();
    final GuardProbe<Order, OrderStatus> allPacked = new GuardProbe<>();
    final Probe<Item, ItemStatus> packItem = new Probe<>();
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
        chargeCard.behaviour = (TransitionContext<Order, OrderStatus> ctx) -> CompletableFuture.supplyAsync(() -> {
            sleep(50);
            return ActionOutcome.ok();
        }, virtual);
        Order order = new Order("o1", OrderStatus.NEW);
        Outcome<Order, OrderStatus> outcome = registry(Duration.ofSeconds(5)).of(ORDERS).transitionTo(order, OrderStatus.PAID);
        assertInstanceOf(Outcome.Applied.class, outcome);
        assertEquals(OrderStatus.PAID, order.status());
    }

    @Test
    void cascadeBeforeReturningTheStageUnderASingleCarrier() {
        chargeCard.behaviour = (TransitionContext<Order, OrderStatus> ctx) -> {
            ctx.cascade(ITEMS, ctx.entity().items().get(0), ItemStatus.PACKING);
            return CompletableFuture.supplyAsync(() -> {
                sleep(50);
                return ActionOutcome.ok();
            }, virtual);
        };
        Order order = new Order("o1", OrderStatus.NEW);
        Item item = new Item("i1", ItemStatus.PENDING, Optional.of(order));
        StateMachine<Order, OrderStatus> orders = registry(Duration.ofSeconds(5)).of(ORDERS);
        Outcome<Order, OrderStatus> outcome = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> orders.async().transitionTo(order, OrderStatus.PAID).join());
        assertInstanceOf(Outcome.Applied.class, outcome);
        assertEquals(OrderStatus.PAID, order.status());
        assertEquals(ItemStatus.PACKING, item.status());
    }

    @Test
    void stageThatNeverCompletesAbortsAndIsLeftAlive() {
        CompletableFuture<ActionOutcome> never = new CompletableFuture<>();
        chargeCard.behaviour = (TransitionContext<Order, OrderStatus> ctx) -> never;
        Order order = new Order("o1", OrderStatus.NEW);
        StateMachine<Order, OrderStatus> orders = registry(Duration.ofMillis(300)).of(ORDERS);
        long start = System.nanoTime();
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class, () -> orders.transitionTo(order, OrderStatus.PAID));
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
        chargeCard.behaviour = (TransitionContext<Order, OrderStatus> ctx) -> CompletableFuture.failedFuture(
                new CompletionException(new ExecutionException(new IOException("gateway down"))));
        Order order = new Order("o1", OrderStatus.NEW);
        Outcome<Order, OrderStatus> outcome = registry(Duration.ofSeconds(5)).of(ORDERS).transitionTo(order, OrderStatus.PAID);
        assertInstanceOf(Outcome.Failed.class, outcome);
        Outcome.Failed<Order, OrderStatus> failed = (Outcome.Failed<Order, OrderStatus>) outcome;
        assertInstanceOf(IOException.class, failed.cause().orElseThrow());
        assertEquals(OrderStatus.PAYMENT_DECLINED, order.status());
    }

    @Test
    void aSynchronousThrowBeforeTheStageIsUnwrappedBeforeFailsOn() {
        chargeCard.behaviour = (TransitionContext<Order, OrderStatus> ctx) -> {
            throw new CompletionException(new IOException("down"));
        };
        Order order = new Order("o1", OrderStatus.NEW);
        Outcome<Order, OrderStatus> outcome = registry(Duration.ofSeconds(5)).of(ORDERS).transitionTo(order, OrderStatus.PAID);
        assertInstanceOf(Outcome.Failed.class, outcome);
        Outcome.Failed<Order, OrderStatus> failed = (Outcome.Failed<Order, OrderStatus>) outcome;
        assertEquals(OrderStatus.PAYMENT_DECLINED, failed.movedTo());
        assertInstanceOf(IOException.class, failed.cause().orElseThrow());
        assertEquals(OrderStatus.PAYMENT_DECLINED, order.status());
    }

    @Test
    void cancelledStageIsATechnicalFailure() {
        chargeCard.behaviour = (TransitionContext<Order, OrderStatus> ctx) -> {
            CompletableFuture<ActionOutcome> cancelled = new CompletableFuture<>();
            cancelled.cancel(true);
            return cancelled;
        };
        Order order = new Order("o1", OrderStatus.NEW);
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                () -> registry(Duration.ofSeconds(5)).of(ORDERS).transitionTo(order, OrderStatus.PAID));
        assertInstanceOf(CancellationException.class, error.getCause());
        assertEquals(OrderStatus.NEW, order.status());
    }

    @Test
    void usingTheContextFromInsideTheStageIsRejected() {
        chargeCard.behaviour = (TransitionContext<Order, OrderStatus> ctx) -> CompletableFuture
                .supplyAsync(ActionOutcome::ok)
                .thenApplyAsync((ActionOutcome outcome) -> {
                    ctx.peer(ITEMS);
                    return outcome;
                });
        Order order = new Order("o1", OrderStatus.NEW);
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                () -> registry(Duration.ofSeconds(5)).of(ORDERS).transitionTo(order, OrderStatus.PAID));
        assertInstanceOf(WrongThreadException.class, error.getCause());
    }

    @Test
    void aLaggingActionFindsAStaleContext() {
        AtomicReference<TransitionContext<Order, OrderStatus>> captured = new AtomicReference<>();
        chargeCard.behaviour = (TransitionContext<Order, OrderStatus> ctx) -> {
            captured.set(ctx);
            return new CompletableFuture<>();
        };
        Order order = new Order("o1", OrderStatus.NEW);
        assertThrows(TransitionAbortedException.class,
                () -> registry(Duration.ofMillis(200)).of(ORDERS).transitionTo(order, OrderStatus.PAID));
        assertThrows(StaleContextException.class, () -> captured.get().peer(ITEMS));
    }

    @Test
    void asyncFacadeNeverRunsOnTheCallerThread() {
        AtomicReference<Thread> seen = new AtomicReference<>();
        chargeCard.behaviour = (TransitionContext<Order, OrderStatus> ctx) -> {
            seen.set(Thread.currentThread());
            return CompletableFuture.completedFuture(ActionOutcome.ok());
        };
        Order order = new Order("o1", OrderStatus.NEW);
        Outcome<Order, OrderStatus> outcome = registry(Duration.ofSeconds(5)).of(ORDERS).async().transitionTo(order, OrderStatus.PAID).join();
        assertInstanceOf(Outcome.Applied.class, outcome);
        assertNotSame(Thread.currentThread(), seen.get());
        assertTrue(seen.get().isVirtual());
    }

    @Test
    void aClosedRegistryFailsFuturesInsteadOfThrowing() {
        MachineRegistry closed = registry(Duration.ofSeconds(5));
        StateMachine<Order, OrderStatus> orders = closed.of(ORDERS);
        closed.close();
        CompletableFuture<Outcome<Order, OrderStatus>> future = orders.async().transitionTo(new Order("o1", OrderStatus.NEW), OrderStatus.PAID);
        assertTrue(future.isCompletedExceptionally());
        CompletionException error = assertThrows(CompletionException.class, future::join);
        assertInstanceOf(RejectedExecutionException.class, error.getCause());
    }

    @Test
    void asyncGuardIsAwaited() {
        GraphDefinition<Doc, DocStatus> graph = StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED)
                .from(DRAFT).to(REVIEW).move()
                    .asyncGuard("Slow", (GuardContext<Doc, DocStatus> ctx) -> CompletableFuture.supplyAsync(() -> GuardResult.reject("not yet"), virtual))
                    .stay().forbidOthers("x")
                .from(REVIEW).to(PUBLISHED).move().stay().forbidOthers("x")
                .from(PUBLISHED).to(ARCHIVED).move().stay().forbidOthers("x")
                .from(ARCHIVED).stay().forbidOthers("x")
                .build();
        StateMachine<Doc, DocStatus> docs = StateMachines.from(graph).state(Doc::status, Doc::setStatus).asyncTimeout(Duration.ofSeconds(2)).build();
        Outcome<Doc, DocStatus> outcome = docs.transitionTo(new Doc("1", DRAFT), REVIEW);
        assertInstanceOf(Outcome.Rejected.class, outcome);
        Outcome.Rejected<Doc, DocStatus> rejected = (Outcome.Rejected<Doc, DocStatus>) outcome;
        assertEquals(Optional.of("Slow"), rejected.guard());
    }

    @Test
    void anEdgeTimeoutOnlyShortensTheDeadline() {
        GraphDefinition<Doc, DocStatus> graph = StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED)
                .from(DRAFT).to(REVIEW).asyncAction("Slow", (TransitionContext<Doc, DocStatus> ctx) -> new CompletableFuture<>())
                                       .timeout(Duration.ofMillis(200))
                    .stay().forbidOthers("x")
                .from(REVIEW).to(PUBLISHED).move().stay().forbidOthers("x")
                .from(PUBLISHED).to(ARCHIVED).move().stay().forbidOthers("x")
                .from(ARCHIVED).stay().forbidOthers("x")
                .build();
        StateMachine<Doc, DocStatus> docs = StateMachines.from(graph).state(Doc::status, Doc::setStatus).asyncTimeout(Duration.ofSeconds(30)).build();
        long start = System.nanoTime();
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class, () -> docs.transitionTo(new Doc("1", DRAFT), REVIEW));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
        assertTrue(elapsedMillis < 5_000, "took " + elapsedMillis + " ms");
        assertInstanceOf(TimeoutException.class, error.getCause());
    }
}
