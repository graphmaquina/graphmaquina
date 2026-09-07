package io.stategraph.runtime;

import static io.stategraph.fixtures.Keys.ITEMS;
import static io.stategraph.fixtures.Keys.ORDERS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.stategraph.api.ActionOutcome;
import io.stategraph.api.ActionRecord;
import io.stategraph.api.ActionStatus;
import io.stategraph.api.CascadePolicy;
import io.stategraph.api.EdgeKind;
import io.stategraph.api.Outcome;
import io.stategraph.api.StateMachine;
import io.stategraph.api.TransitionContext;
import io.stategraph.api.TransitionRecord;
import io.stategraph.error.AmbiguousIdentityException;
import io.stategraph.error.CascadeCycleException;
import io.stategraph.error.CascadeDepthExceededException;
import io.stategraph.error.CascadeException;
import io.stategraph.error.PoisonedUnitOfWorkException;
import io.stategraph.error.TransitionAbortedException;
import io.stategraph.error.UnhandledFailureException;
import io.stategraph.error.UnknownPeerMachineException;
import io.stategraph.fixtures.GuardProbe;
import io.stategraph.fixtures.Item;
import io.stategraph.fixtures.ItemStatus;
import io.stategraph.fixtures.Order;
import io.stategraph.fixtures.OrderGraphs;
import io.stategraph.fixtures.OrderStatus;
import io.stategraph.fixtures.Probe;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CascadeTest {

    final Probe<Order, OrderStatus> shipOrder = new Probe<>();
    final Probe<Order, OrderStatus> cancelOrder = new Probe<>();
    final GuardProbe<Order, OrderStatus> retryBudget = new GuardProbe<>();
    final GuardProbe<Order, OrderStatus> allPacked = new GuardProbe<>();
    final Probe<Item, ItemStatus> packItem = new Probe<>();

    private MachineRegistry registry(int maxCascadeDepth) {
        return MachineRegistry.builder()
                .add(StateMachines.from(OrderGraphs.order(
                                (TransitionContext<Order, OrderStatus> ctx) -> CompletableFuture.completedFuture(ActionOutcome.ok()),
                                shipOrder, cancelOrder, retryBudget, allPacked))
                        .state(Order::status, Order::setStatus).identity(Order::id).asyncTimeout(Duration.ofSeconds(5)))
                .add(StateMachines.from(OrderGraphs.item(packItem))
                        .state(Item::status, Item::setStatus).identity(Item::id))
                .maxCascadeDepth(maxCascadeDepth)
                .build();
    }

    private void cancelCascades(CascadePolicy policy) {
        cancelOrder.behaviour = (TransitionContext<Order, OrderStatus> ctx) -> {
            ctx.cascadeAll(ITEMS, ctx.entity().items(), ItemStatus.CANCELLED, policy);
            return ActionOutcome.ok();
        };
    }

    @Test
    void cascadeMovesPeersAtomically() {
        cancelCascades(CascadePolicy.COLLECT);
        Order order = new Order("o1", OrderStatus.NEW);
        Item i1 = new Item("i1", ItemStatus.PENDING, Optional.of(order));
        Item i2 = new Item("i2", ItemStatus.PENDING, Optional.of(order));
        StateMachine<Order, OrderStatus> orders = registry(10).of(ORDERS);
        Outcome<Order, OrderStatus> outcome = orders.transitionTo(order, OrderStatus.CANCELLED);
        assertInstanceOf(Outcome.Applied.class, outcome);
        Outcome.Applied<Order, OrderStatus> applied = (Outcome.Applied<Order, OrderStatus>) outcome;
        assertEquals(OrderStatus.CANCELLED, order.status());
        assertEquals(ItemStatus.CANCELLED, i1.status());
        assertEquals(ItemStatus.CANCELLED, i2.status());
        List<TransitionRecord<?, ?>> records = applied.records();
        assertEquals(3, records.size());
        assertEquals(0, records.get(0).depth());
        assertEquals(Optional.of("CancelOrder"), records.get(0).action());
        assertEquals(1, records.get(1).depth());
        assertEquals(EdgeKind.MOVE, records.get(1).kind());
        assertEquals(ITEMS, records.get(2).key());
    }

    @Test
    void parentFailureRevertsCascadedPeers() {
        cancelOrder.behaviour = (TransitionContext<Order, OrderStatus> ctx) -> {
            ctx.cascadeAll(ITEMS, ctx.entity().items(), ItemStatus.CANCELLED, CascadePolicy.FAIL_FAST);
            return ActionOutcome.failed("change of mind");
        };
        Order order = new Order("o1", OrderStatus.NEW);
        Item i1 = new Item("i1", ItemStatus.PENDING, Optional.of(order));
        StateMachine<Order, OrderStatus> orders = registry(10).of(ORDERS);
        assertThrows(UnhandledFailureException.class, () -> orders.transitionTo(order, OrderStatus.CANCELLED));
        assertEquals(OrderStatus.NEW, order.status());
        assertEquals(ItemStatus.PENDING, i1.status());
    }

    @Test
    void forbidInsideACascadeAbortsEverything() {
        cancelCascades(CascadePolicy.FAIL_FAST);
        Order order = new Order("o1", OrderStatus.NEW);
        Item packed = new Item("i1", ItemStatus.PACKED, Optional.of(order));
        Item pending = new Item("i2", ItemStatus.PENDING, Optional.of(order));
        StateMachine<Order, OrderStatus> orders = registry(10).of(ORDERS);
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class, () -> orders.transitionTo(order, OrderStatus.CANCELLED));
        assertTrue(error.getMessage().contains("rejected inside a cascade"));
        assertTrue(error.getMessage().contains("packed is final"));
        assertEquals(OrderStatus.NEW, order.status());
        assertEquals(ItemStatus.PACKED, packed.status());
        assertEquals(ItemStatus.PENDING, pending.status());
        assertEquals(List.of(ActionStatus.THREW), error.journal().stream().map(ActionRecord::status).toList());
        assertEquals("CancelOrder", error.journal().get(0).action());
    }

    @Test
    void parentCannotSwallowACascadeFailure() {
        cancelOrder.behaviour = (TransitionContext<Order, OrderStatus> ctx) -> {
            try {
                ctx.cascadeAll(ITEMS, ctx.entity().items(), ItemStatus.CANCELLED, CascadePolicy.FAIL_FAST);
            } catch (TransitionAbortedException swallowed) {
                // the parent tries to carry on as if nothing happened
            }
            return ActionOutcome.ok();
        };
        Order order = new Order("o1", OrderStatus.NEW);
        new Item("i1", ItemStatus.PACKED, Optional.of(order));
        StateMachine<Order, OrderStatus> orders = registry(10).of(ORDERS);
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class, () -> orders.transitionTo(order, OrderStatus.CANCELLED));
        assertTrue(error.getMessage().contains("rejected inside a cascade"));
        assertEquals(OrderStatus.NEW, order.status());
    }

    @Test
    void collectTriesEveryElementAndAggregates() {
        cancelCascades(CascadePolicy.COLLECT);
        Order order = new Order("o1", OrderStatus.NEW);
        Item i1 = new Item("i1", ItemStatus.PENDING, Optional.of(order));
        Item i2 = new Item("i2", ItemStatus.PACKED, Optional.of(order));
        Item i3 = new Item("i3", ItemStatus.PENDING, Optional.of(order));
        StateMachine<Order, OrderStatus> orders = registry(10).of(ORDERS);
        CascadeException error = assertThrows(CascadeException.class, () -> orders.transitionTo(order, OrderStatus.CANCELLED));
        assertEquals(1, error.errors().size());
        assertEquals(OrderStatus.NEW, order.status());
        assertEquals(ItemStatus.PENDING, i1.status());
        assertEquals(ItemStatus.PACKED, i2.status());
        assertEquals(ItemStatus.PENDING, i3.status());
    }

    @Test
    void failFastIsNotAggregated() {
        cancelCascades(CascadePolicy.FAIL_FAST);
        Order order = new Order("o1", OrderStatus.NEW);
        new Item("i1", ItemStatus.PACKED, Optional.of(order));
        new Item("i2", ItemStatus.PENDING, Optional.of(order));
        StateMachine<Order, OrderStatus> orders = registry(10).of(ORDERS);
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class, () -> orders.transitionTo(order, OrderStatus.CANCELLED));
        assertTrue(!(error instanceof CascadeException));
    }

    @Test
    void convergentCascadeIsAnIdempotentNoOp() {
        shipOrder.behaviour = (TransitionContext<Order, OrderStatus> ctx) -> {
            ctx.cascade(ITEMS, ctx.entity().items().get(0), ItemStatus.PACKED);
            return ActionOutcome.ok();
        };
        packItem.behaviour = (TransitionContext<Item, ItemStatus> ctx) -> {
            Outcome<Order, OrderStatus> back = ctx.cascade(ORDERS, ctx.entity().order().orElseThrow(), OrderStatus.SHIPPED);
            assertInstanceOf(Outcome.Ignored.class, back);
            return ActionOutcome.ok();
        };
        Order order = new Order("o1", OrderStatus.PAID);
        Item item = new Item("i1", ItemStatus.PACKING, Optional.of(order));
        StateMachine<Order, OrderStatus> orders = registry(10).of(ORDERS);
        Outcome<Order, OrderStatus> outcome = orders.transitionTo(order, OrderStatus.SHIPPED);
        assertInstanceOf(Outcome.Applied.class, outcome);
        Outcome.Applied<Order, OrderStatus> applied = (Outcome.Applied<Order, OrderStatus>) outcome;
        assertEquals(OrderStatus.SHIPPED, order.status());
        assertEquals(ItemStatus.PACKED, item.status());
        assertEquals(3, applied.records().size());
        assertTrue(applied.records().get(2).convergent());
        assertEquals(2, applied.records().get(2).depth());
    }

    @Test
    void reentryWithADifferentTargetIsACycle() {
        shipOrder.behaviour = (TransitionContext<Order, OrderStatus> ctx) -> {
            ctx.cascade(ITEMS, ctx.entity().items().get(0), ItemStatus.PACKED);
            return ActionOutcome.ok();
        };
        packItem.behaviour = (TransitionContext<Item, ItemStatus> ctx) -> {
            ctx.cascade(ORDERS, ctx.entity().order().orElseThrow(), OrderStatus.CANCELLED);
            return ActionOutcome.ok();
        };
        Order order = new Order("o1", OrderStatus.PAID);
        Item item = new Item("i1", ItemStatus.PACKING, Optional.of(order));
        StateMachine<Order, OrderStatus> orders = registry(10).of(ORDERS);
        CascadeCycleException error = assertThrows(CascadeCycleException.class, () -> orders.transitionTo(order, OrderStatus.SHIPPED));
        assertEquals(List.of("Order/OrderStatus#o1", "Item/ItemStatus#i1", "Order/OrderStatus#o1"), error.route());
        assertEquals(OrderStatus.SHIPPED, error.inFlightTarget());
        assertEquals(OrderStatus.CANCELLED, error.requestedTarget());
        assertEquals(OrderStatus.PAID, order.status());
        assertEquals(ItemStatus.PACKING, item.status());
    }

    @Test
    void depthIsBoundedByTheRootMachineSetting() {
        shipOrder.behaviour = (TransitionContext<Order, OrderStatus> ctx) -> {
            ctx.cascade(ITEMS, ctx.entity().items().get(0), ItemStatus.PACKED);
            return ActionOutcome.ok();
        };
        packItem.behaviour = (TransitionContext<Item, ItemStatus> ctx) -> {
            if (ctx.entity().id().equals("i1")) {
                ctx.cascade(ITEMS, ctx.entity().order().orElseThrow().items().get(1), ItemStatus.PACKED);
            }
            return ActionOutcome.ok();
        };
        Order order = new Order("o1", OrderStatus.PAID);
        new Item("i1", ItemStatus.PACKING, Optional.of(order));
        new Item("i2", ItemStatus.PACKING, Optional.of(order));
        StateMachine<Order, OrderStatus> orders = registry(1).of(ORDERS);
        CascadeDepthExceededException error = assertThrows(CascadeDepthExceededException.class, () -> orders.transitionTo(order, OrderStatus.SHIPPED));
        assertEquals(1, error.maxDepth());
        assertEquals(OrderStatus.PAID, order.status());
    }

    @Test
    void peerViewReadsThePendingStateOfTheParent() {
        AtomicReference<OrderStatus> seen = new AtomicReference<>();
        AtomicReference<OrderStatus> raw = new AtomicReference<>();
        shipOrder.behaviour = (TransitionContext<Order, OrderStatus> ctx) -> {
            ctx.cascade(ITEMS, ctx.entity().items().get(0), ItemStatus.PACKED);
            return ActionOutcome.ok();
        };
        packItem.behaviour = (TransitionContext<Item, ItemStatus> ctx) -> {
            Order owner = ctx.entity().order().orElseThrow();
            seen.set(ctx.peer(ORDERS).stateOf(owner));
            raw.set(owner.status());
            return ActionOutcome.ok();
        };
        Order order = new Order("o1", OrderStatus.PAID);
        new Item("i1", ItemStatus.PACKING, Optional.of(order));
        registry(10).of(ORDERS).transitionTo(order, OrderStatus.SHIPPED);
        assertEquals(OrderStatus.SHIPPED, seen.get());
        assertEquals(OrderStatus.PAID, raw.get());
    }

    @Test
    void twoInstancesWithTheSameIdentityAreAmbiguous() {
        Order first = new Order("o1", OrderStatus.PAID);
        Order second = new Order("o1", OrderStatus.PAID);
        Item item = new Item("i1", ItemStatus.PACKING, Optional.of(second));
        first.add(item);
        shipOrder.behaviour = (TransitionContext<Order, OrderStatus> ctx) -> {
            ctx.cascade(ITEMS, item, ItemStatus.PACKED);
            return ActionOutcome.ok();
        };
        packItem.behaviour = (TransitionContext<Item, ItemStatus> ctx) -> {
            ctx.cascade(ORDERS, second, OrderStatus.SHIPPED);
            return ActionOutcome.ok();
        };
        StateMachine<Order, OrderStatus> orders = registry(10).of(ORDERS);
        AmbiguousIdentityException error = assertThrows(AmbiguousIdentityException.class, () -> orders.transitionTo(first, OrderStatus.SHIPPED));
        assertEquals("o1", error.id());
        assertEquals(OrderStatus.PAID, first.status());
    }

    @Test
    void unknownPeerMachineIsAnError() {
        cancelCascades(CascadePolicy.FAIL_FAST);
        Order order = new Order("o1", OrderStatus.NEW);
        new Item("i1", ItemStatus.PENDING, Optional.of(order));
        MachineRegistry onlyOrders = MachineRegistry.builder()
                .add(StateMachines.from(OrderGraphs.order(
                                (TransitionContext<Order, OrderStatus> ctx) -> CompletableFuture.completedFuture(ActionOutcome.ok()),
                                shipOrder, cancelOrder, retryBudget, allPacked))
                        .state(Order::status, Order::setStatus).identity(Order::id).asyncTimeout(Duration.ofSeconds(5)))
                .build();
        assertThrows(UnknownPeerMachineException.class, () -> onlyOrders.of(ORDERS).transitionTo(order, OrderStatus.CANCELLED));
        assertEquals(OrderStatus.NEW, order.status());
    }

    @Test
    void afterACascadeFailureTheUnitOfWorkIsPoisoned() {
        AtomicReference<RuntimeException> later = new AtomicReference<>();
        cancelOrder.behaviour = (TransitionContext<Order, OrderStatus> ctx) -> {
            try {
                ctx.cascadeAll(ITEMS, ctx.entity().items(), ItemStatus.CANCELLED, CascadePolicy.FAIL_FAST);
            } catch (TransitionAbortedException first) {
                try {
                    ctx.peer(ITEMS);
                } catch (RuntimeException second) {
                    later.set(second);
                }
            }
            return ActionOutcome.ok();
        };
        Order order = new Order("o1", OrderStatus.NEW);
        new Item("i1", ItemStatus.PACKED, Optional.of(order));
        StateMachine<Order, OrderStatus> orders = registry(10).of(ORDERS);
        assertThrows(TransitionAbortedException.class, () -> orders.transitionTo(order, OrderStatus.CANCELLED));
        PoisonedUnitOfWorkException poisoned = assertInstanceOf(PoisonedUnitOfWorkException.class, later.get());
        assertTrue(poisoned.original().getMessage().contains("rejected inside a cascade"));
    }
}
