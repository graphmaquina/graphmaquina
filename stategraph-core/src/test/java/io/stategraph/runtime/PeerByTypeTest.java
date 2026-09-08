package io.stategraph.runtime;

import static io.stategraph.fixtures.Keys.ORDERS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.stategraph.api.ActionOutcome;
import io.stategraph.api.CascadePolicy;
import io.stategraph.api.GuardContext;
import io.stategraph.api.GuardResult;
import io.stategraph.api.Outcome;
import io.stategraph.api.StateMachine;
import io.stategraph.api.TransitionContext;
import io.stategraph.error.UnknownFacadeException;
import io.stategraph.fixtures.Facades;
import io.stategraph.fixtures.Item;
import io.stategraph.fixtures.ItemStatus;
import io.stategraph.fixtures.Order;
import io.stategraph.fixtures.OrderGraphs;
import io.stategraph.fixtures.OrderStatus;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PeerByTypeTest {

    final AtomicReference<Thread> guardThread = new AtomicReference<>();

    private MachineRegistry registry(boolean registerItemsFacade) {
        StateMachines.Builder<Item, ItemStatus> items = StateMachines.from(
                        OrderGraphs.item((TransitionContext<Item, ItemStatus> ctx) -> ActionOutcome.ok()))
                .state(Item::status, Item::setStatus).identity(Item::id);
        if (registerItemsFacade) {
            items.as(Facades.Items.class);
        }
        return MachineRegistry.builder()
                .add(StateMachines.from(OrderGraphs.order(
                                (TransitionContext<Order, OrderStatus> ctx) -> CompletableFuture.completedFuture(ActionOutcome.ok()),
                                (TransitionContext<Order, OrderStatus> ctx) -> ActionOutcome.ok(),
                                (TransitionContext<Order, OrderStatus> ctx) -> {
                                    List<Outcome<Item, ItemStatus>> outcomes = ctx.cascadeAll(Facades.Items.class,
                                            ctx.entity().items(), ItemStatus.CANCELLED, CascadePolicy.COLLECT);
                                    return outcomes.size() == ctx.entity().items().size() ? ActionOutcome.ok() : ActionOutcome.failed("missing");
                                },
                                (GuardContext<Order, OrderStatus> ctx) -> GuardResult.pass(),
                                (GuardContext<Order, OrderStatus> ctx) -> {
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
            StateMachine<Order, OrderStatus> orders = registry.of(ORDERS);
            Order order = new Order("o1", OrderStatus.NEW);
            new Item("i1", ItemStatus.PENDING, Optional.of(order));
            assertInstanceOf(Outcome.Applied.class, orders.transitionTo(order, OrderStatus.CANCELLED));
            assertEquals(ItemStatus.CANCELLED, order.items().get(0).status());

            Order paid = new Order("o2", OrderStatus.PAID);
            new Item("i2", ItemStatus.PENDING, Optional.of(paid));
            Outcome<Order, OrderStatus> rejected = orders.transitionTo(paid, OrderStatus.SHIPPED);
            assertInstanceOf(Outcome.Rejected.class, rejected);
        }
    }

    @Test
    void unregisteredFacadeTypeIsAnUnknownFacade() {
        try (MachineRegistry registry = registry(false)) {
            Order order = new Order("o1", OrderStatus.NEW);
            new Item("i1", ItemStatus.PENDING, Optional.of(order));
            assertThrows(UnknownFacadeException.class, () -> registry.of(ORDERS).transitionTo(order, OrderStatus.CANCELLED));
        }
    }

    @Test
    void singleCascadeByFacadeType() {
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(OrderGraphs.order(
                                (TransitionContext<Order, OrderStatus> ctx) -> CompletableFuture.completedFuture(ActionOutcome.ok()),
                                (TransitionContext<Order, OrderStatus> ctx) -> ActionOutcome.ok(),
                                (TransitionContext<Order, OrderStatus> ctx) -> {
                                    ctx.cascade(Facades.Items.class, ctx.entity().items().get(0), ItemStatus.CANCELLED);
                                    return ActionOutcome.ok();
                                },
                                (GuardContext<Order, OrderStatus> ctx) -> GuardResult.pass(),
                                (GuardContext<Order, OrderStatus> ctx) -> GuardResult.pass()))
                        .state(Order::status, Order::setStatus).identity(Order::id).asyncTimeout(Duration.ofSeconds(5)))
                .add(StateMachines.from(OrderGraphs.item((TransitionContext<Item, ItemStatus> ctx) -> ActionOutcome.ok()))
                        .state(Item::status, Item::setStatus).identity(Item::id).as(Facades.Items.class))
                .build()) {
            Order order = new Order("o1", OrderStatus.NEW);
            Item item = new Item("i1", ItemStatus.PENDING, Optional.of(order));
            assertInstanceOf(Outcome.Applied.class, registry.of(ORDERS).transitionTo(order, OrderStatus.CANCELLED));
            assertEquals(ItemStatus.CANCELLED, item.status());
        }
    }

    @Test
    void supplyAsyncRunsOffTheCallingThreadAndFailsAfterClose() throws InterruptedException, ExecutionException {
        MachineRegistry registry = registry(true);
        StateMachine<Order, OrderStatus> orders = registry.of(ORDERS);
        Order order = new Order("o1", OrderStatus.NEW);
        Thread caller = Thread.currentThread();
        Thread worker = orders.supplyAsync(Thread::currentThread).get();
        assertNotSame(caller, worker);
        assertInstanceOf(Outcome.Applied.class, orders.supplyAsync(() -> orders.transitionTo(order, OrderStatus.PAID)).get());
        registry.close();
        CompletableFuture<Integer> afterClose = orders.supplyAsync(() -> 1);
        assertTrue(afterClose.isCompletedExceptionally());
    }

    @Test
    void asTwiceOnTheSameBuilderIsRejected() {
        StateMachines.Builder<Order, OrderStatus> wrong = StateMachines.from(OrderGraphs.order(
                        (TransitionContext<Order, OrderStatus> ctx) -> CompletableFuture.completedFuture(ActionOutcome.ok()),
                        (TransitionContext<Order, OrderStatus> ctx) -> ActionOutcome.ok(),
                        (TransitionContext<Order, OrderStatus> ctx) -> ActionOutcome.ok(),
                        (GuardContext<Order, OrderStatus> ctx) -> GuardResult.pass(),
                        (GuardContext<Order, OrderStatus> ctx) -> GuardResult.pass()))
                .state(Order::status, Order::setStatus).identity(Order::id).asyncTimeout(Duration.ofSeconds(5));
        assertThrows(IllegalStateException.class, () -> wrong.as(Facades.Orders.class).as(Facades.Orders.class));
    }
}
