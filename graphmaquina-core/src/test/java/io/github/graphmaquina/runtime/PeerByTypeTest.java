package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.Keys.ORDERS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.CascadePolicy;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.error.UnknownFacadeException;
import io.github.graphmaquina.fixtures.Facades;
import io.github.graphmaquina.fixtures.Item;
import io.github.graphmaquina.fixtures.ItemStatus;
import io.github.graphmaquina.fixtures.ItemTransition;
import io.github.graphmaquina.fixtures.Order;
import io.github.graphmaquina.fixtures.OrderGraphs;
import io.github.graphmaquina.fixtures.OrderStatus;
import io.github.graphmaquina.fixtures.OrderTransition;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PeerByTypeTest {

    final AtomicReference<Thread> guardThread = new AtomicReference<>();

    private MachineRegistry registry(boolean registerItemsFacade) {
        StateMachines.Builder<Item, ItemStatus, ItemTransition> items = StateMachines.from(
                        OrderGraphs.item((TransitionContext<Item, ItemStatus, ItemTransition> ctx) -> ActionOutcome.ok()))
                .state(Item::status, Item::setStatus).identity(Item::id);
        if (registerItemsFacade) {
            items.as(Facades.Items.class);
        }
        return MachineRegistry.builder()
                .add(StateMachines.from(OrderGraphs.order(
                                (TransitionContext<Order, OrderStatus, OrderTransition> ctx) ->
                                        CompletableFuture.completedFuture(ActionOutcome.ok()),
                                (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> ActionOutcome.ok(),
                                (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> {
                                    List<Outcome<Item, ItemStatus, ItemTransition>> outcomes = ctx.cascadeAll(
                                            Facades.Items.class, ctx.entity().items(), ItemTransition.CANCEL,
                                            CascadePolicy.COLLECT);
                                    return outcomes.size() == ctx.entity().items().size()
                                            ? ActionOutcome.ok() : ActionOutcome.failed("missing");
                                },
                                (GuardContext<Order, OrderStatus, OrderTransition> ctx) -> GuardResult.pass(),
                                (GuardContext<Order, OrderStatus, OrderTransition> ctx) -> {
                                    guardThread.set(Thread.currentThread());
                                    return ctx.peer(Facades.Items.class).allIn(ctx.entity().items(), ItemStatus.PACKED)
                                            ? GuardResult.pass() : GuardResult.reject("unpacked");
                                }))
                        .state(Order::status, Order::setStatus).identity(Order::id).asyncTimeout(Duration.ofSeconds(5))
                        .as(Facades.Orders.class))
                .add(items)
                .build();
    }

    @Test
    void cascadeAllAndPeerResolveTheMachineByFacadeType() {
        try (MachineRegistry registry = registry(true)) {
            StateMachine<Order, OrderStatus, OrderTransition> orders = registry.of(ORDERS);
            Order order = new Order("o1", OrderStatus.NEW);
            new Item("i1", ItemStatus.PENDING, Optional.of(order));
            assertInstanceOf(Outcome.Applied.class, orders.execute(order, OrderTransition.CANCEL));
            assertEquals(ItemStatus.CANCELLED, order.items().get(0).status());

            Order paid = new Order("o2", OrderStatus.PAID);
            new Item("i2", ItemStatus.PENDING, Optional.of(paid));
            Outcome<Order, OrderStatus, OrderTransition> rejected = orders.execute(paid, OrderTransition.SHIP);
            assertInstanceOf(Outcome.Rejected.class, rejected);
        }
    }

    @Test
    void unregisteredFacadeTypeIsAnUnknownFacade() {
        try (MachineRegistry registry = registry(false)) {
            Order order = new Order("o1", OrderStatus.NEW);
            new Item("i1", ItemStatus.PENDING, Optional.of(order));
            assertThrows(UnknownFacadeException.class, () -> registry.of(ORDERS).execute(order, OrderTransition.CANCEL));
        }
    }

    @Test
    void singleCascadeByFacadeType() {
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(OrderGraphs.order(
                                (TransitionContext<Order, OrderStatus, OrderTransition> ctx) ->
                                        CompletableFuture.completedFuture(ActionOutcome.ok()),
                                (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> ActionOutcome.ok(),
                                (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> {
                                    ctx.cascade(Facades.Items.class, ctx.entity().items().get(0), ItemTransition.CANCEL);
                                    return ActionOutcome.ok();
                                },
                                (GuardContext<Order, OrderStatus, OrderTransition> ctx) -> GuardResult.pass(),
                                (GuardContext<Order, OrderStatus, OrderTransition> ctx) -> GuardResult.pass()))
                        .state(Order::status, Order::setStatus).identity(Order::id).asyncTimeout(Duration.ofSeconds(5)))
                .add(StateMachines.from(OrderGraphs.item(
                                (TransitionContext<Item, ItemStatus, ItemTransition> ctx) -> ActionOutcome.ok()))
                        .state(Item::status, Item::setStatus).identity(Item::id).as(Facades.Items.class))
                .build()) {
            Order order = new Order("o1", OrderStatus.NEW);
            Item item = new Item("i1", ItemStatus.PENDING, Optional.of(order));
            assertInstanceOf(Outcome.Applied.class, registry.of(ORDERS).execute(order, OrderTransition.CANCEL));
            assertEquals(ItemStatus.CANCELLED, item.status());
        }
    }

    /**
     * A cascade addressed by facade type starts a step of its own, so the child runs with no input of the call
     * that cascaded to it, and the parent keeps reading the very value it was given. The child is driven from
     * PACKING to PACKED, the one edge of its graph that runs an action, so the assertion observes a real action.
     */
    @Test
    void cascadeByClassAndCascadeAllByClassDoNotInheritInput() {
        for (boolean all : List.of(false, true)) {
            Object value = new Object();
            AtomicInteger childCalls = new AtomicInteger();
            try (MachineRegistry registry = MachineRegistry.builder()
                    .add(StateMachines.from(OrderGraphs.order(
                                    (TransitionContext<Order, OrderStatus, OrderTransition> ctx) ->
                                            CompletableFuture.completedFuture(ActionOutcome.ok()),
                                    (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> ActionOutcome.ok(),
                                    (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> {
                                        assertSame(value, ctx.input().orElseThrow());
                                        if (all) {
                                            ctx.cascadeAll(Facades.Items.class, ctx.entity().items(),
                                                    ItemTransition.PACK, CascadePolicy.COLLECT);
                                        } else {
                                            ctx.cascade(Facades.Items.class, ctx.entity().items().getFirst(),
                                                    ItemTransition.PACK);
                                        }
                                        assertSame(value, ctx.input().orElseThrow());
                                        return ActionOutcome.ok();
                                    },
                                    (GuardContext<Order, OrderStatus, OrderTransition> ctx) -> GuardResult.pass(),
                                    (GuardContext<Order, OrderStatus, OrderTransition> ctx) -> GuardResult.pass()))
                            .state(Order::status, Order::setStatus).identity(Order::id)
                            .asyncTimeout(Duration.ofSeconds(5)))
                    .add(StateMachines.from(OrderGraphs.item(
                                    (TransitionContext<Item, ItemStatus, ItemTransition> ctx) -> {
                                        assertTrue(ctx.input().isEmpty());
                                        childCalls.incrementAndGet();
                                        return ActionOutcome.ok();
                                    })).state(Item::status, Item::setStatus).identity(Item::id).as(Facades.Items.class))
                    .build()) {
                Order order = new Order("o", OrderStatus.NEW);
                Item item = new Item("i", ItemStatus.PACKING, Optional.of(order));
                assertInstanceOf(Outcome.Applied.class,
                        registry.of(ORDERS).execute(order, OrderTransition.CANCEL, value));
                assertEquals(ItemStatus.PACKED, item.status());
                assertEquals(1, childCalls.get());
            }
        }
    }

    @Test
    void supplyAsyncRunsOffTheCallingThreadAndFailsAfterClose() throws InterruptedException, ExecutionException {
        MachineRegistry registry = registry(true);
        StateMachine<Order, OrderStatus, OrderTransition> orders = registry.of(ORDERS);
        Order order = new Order("o1", OrderStatus.NEW);
        Thread caller = Thread.currentThread();
        Thread worker = orders.supplyAsync(Thread::currentThread).get();
        assertNotSame(caller, worker);
        assertInstanceOf(Outcome.Applied.class,
                orders.supplyAsync(() -> orders.execute(order, OrderTransition.CHARGE)).get());
        registry.close();
        CompletableFuture<Integer> afterClose = orders.supplyAsync(() -> 1);
        assertTrue(afterClose.isCompletedExceptionally());
    }

    @Test
    void asTwiceOnTheSameBuilderIsRejected() {
        StateMachines.Builder<Order, OrderStatus, OrderTransition> wrong = StateMachines.from(OrderGraphs.order(
                        (TransitionContext<Order, OrderStatus, OrderTransition> ctx) ->
                                CompletableFuture.completedFuture(ActionOutcome.ok()),
                        (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> ActionOutcome.ok(),
                        (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> ActionOutcome.ok(),
                        (GuardContext<Order, OrderStatus, OrderTransition> ctx) -> GuardResult.pass(),
                        (GuardContext<Order, OrderStatus, OrderTransition> ctx) -> GuardResult.pass()))
                .state(Order::status, Order::setStatus).identity(Order::id).asyncTimeout(Duration.ofSeconds(5));
        assertThrows(IllegalStateException.class, () -> wrong.as(Facades.Orders.class).as(Facades.Orders.class));
    }
}
