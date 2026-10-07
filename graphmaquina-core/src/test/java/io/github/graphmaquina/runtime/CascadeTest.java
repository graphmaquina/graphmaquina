package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.Keys.ITEMS;
import static io.github.graphmaquina.fixtures.Keys.ORDERS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.ActionRecord;
import io.github.graphmaquina.api.ActionStatus;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.CascadePolicy;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.TransitionKind;
import io.github.graphmaquina.api.TransitionRecord;
import io.github.graphmaquina.error.AmbiguousIdentityException;
import io.github.graphmaquina.error.CascadeCycleException;
import io.github.graphmaquina.error.CascadeDepthExceededException;
import io.github.graphmaquina.error.CascadeException;
import io.github.graphmaquina.error.PoisonedUnitOfWorkException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.error.UnhandledFailureException;
import io.github.graphmaquina.error.UnknownPeerMachineException;
import io.github.graphmaquina.fixtures.GuardProbe;
import io.github.graphmaquina.fixtures.Item;
import io.github.graphmaquina.fixtures.ItemStatus;
import io.github.graphmaquina.fixtures.ItemTransition;
import io.github.graphmaquina.fixtures.Order;
import io.github.graphmaquina.fixtures.OrderGraphs;
import io.github.graphmaquina.fixtures.OrderStatus;
import io.github.graphmaquina.fixtures.OrderTransition;
import io.github.graphmaquina.fixtures.Probe;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Cascades from an order's action to its items: peers moved atomically and reverted with their parent, FAIL_FAST
 * against COLLECT, cycles, the depth limit, peer views, ambiguous identities, an unregistered peer machine, the poison
 * a failed cascade leaves, and the input that a cascaded call does not inherit.
 */
class CascadeTest {

    final Probe<Order, OrderStatus, OrderTransition> shipOrder = new Probe<>();
    final Probe<Order, OrderStatus, OrderTransition> cancelOrder = new Probe<>();
    final GuardProbe<Order, OrderStatus, OrderTransition> retryBudget = new GuardProbe<>();
    final GuardProbe<Order, OrderStatus, OrderTransition> allPacked = new GuardProbe<>();
    final Probe<Item, ItemStatus, ItemTransition> packItem = new Probe<>();

    private MachineRegistry registry(int maxCascadeDepth) {
        return MachineRegistry.builder()
                .add(StateMachines.from(OrderGraphs.order(
                                (TransitionContext<Order, OrderStatus, OrderTransition> ctx) ->
                                        CompletableFuture.completedFuture(ActionOutcome.ok()),
                                shipOrder, cancelOrder, retryBudget, allPacked))
                        .state(Order::status, Order::setStatus).identity(Order::id).asyncTimeout(Duration.ofSeconds(5)))
                .add(StateMachines.from(OrderGraphs.item(packItem))
                        .state(Item::status, Item::setStatus).identity(Item::id))
                .maxCascadeDepth(maxCascadeDepth)
                .build();
    }

    private void cancelCascades(CascadePolicy policy) {
        cancelOrder.behaviour = (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> {
            ctx.cascadeAll(ITEMS, ctx.entity().items(), ItemTransition.CANCEL, policy);
            return ActionOutcome.ok();
        };
    }

    @Test
    void cascadeMovesPeersAtomically() {
        cancelCascades(CascadePolicy.COLLECT);
        Order order = new Order("o1", OrderStatus.NEW);
        Item i1 = new Item("i1", ItemStatus.PENDING, Optional.of(order));
        Item i2 = new Item("i2", ItemStatus.PENDING, Optional.of(order));
        StateMachine<Order, OrderStatus, OrderTransition> orders = registry(10).of(ORDERS);
        Outcome<Order, OrderStatus, OrderTransition> outcome = orders.execute(order, OrderTransition.CANCEL);
        assertInstanceOf(Outcome.Applied.class, outcome);
        Outcome.Applied<Order, OrderStatus, OrderTransition> applied =
                (Outcome.Applied<Order, OrderStatus, OrderTransition>) outcome;
        assertEquals(OrderStatus.CANCELLED, order.status());
        assertEquals(ItemStatus.CANCELLED, i1.status());
        assertEquals(ItemStatus.CANCELLED, i2.status());
        List<TransitionRecord<?, ?, ?>> records = applied.records();
        assertEquals(3, records.size());
        assertEquals(0, records.get(0).depth());
        assertEquals(Optional.of("CancelOrder"), records.get(0).action());
        assertEquals(1, records.get(1).depth());
        assertEquals(TransitionKind.MOVE, records.get(1).kind());
        assertEquals(ItemTransition.CANCEL, records.get(1).transition());
        assertEquals(ITEMS, records.get(2).key());
    }

    @Test
    void parentFailureRevertsCascadedPeers() {
        cancelOrder.behaviour = (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> {
            ctx.cascadeAll(ITEMS, ctx.entity().items(), ItemTransition.CANCEL, CascadePolicy.FAIL_FAST);
            return ActionOutcome.failed("change of mind");
        };
        Order order = new Order("o1", OrderStatus.NEW);
        Item i1 = new Item("i1", ItemStatus.PENDING, Optional.of(order));
        StateMachine<Order, OrderStatus, OrderTransition> orders = registry(10).of(ORDERS);
        assertThrows(UnhandledFailureException.class, () -> orders.execute(order, OrderTransition.CANCEL));
        assertEquals(OrderStatus.NEW, order.status());
        assertEquals(ItemStatus.PENDING, i1.status());
    }

    @Test
    void anAbsentEdgeInsideACascadeAbortsEverything() {
        cancelCascades(CascadePolicy.FAIL_FAST);
        Order order = new Order("o1", OrderStatus.NEW);
        Item packed = new Item("i1", ItemStatus.PACKED, Optional.of(order));
        Item pending = new Item("i2", ItemStatus.PENDING, Optional.of(order));
        StateMachine<Order, OrderStatus, OrderTransition> orders = registry(10).of(ORDERS);
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                () -> orders.execute(order, OrderTransition.CANCEL));
        assertTrue(error.getMessage().contains("rejected inside a cascade"));
        assertTrue(error.getMessage().contains("terminal state PACKED accepts no transitions"));
        assertEquals(OrderStatus.NEW, order.status());
        assertEquals(ItemStatus.PACKED, packed.status());
        assertEquals(ItemStatus.PENDING, pending.status());
        assertEquals(List.of(ActionStatus.THREW), error.journal().stream().map(ActionRecord::status).toList());
        assertEquals("CancelOrder", error.journal().get(0).action());
    }

    @Test
    void parentCannotSwallowACascadeFailure() {
        cancelOrder.behaviour = (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> {
            try {
                ctx.cascadeAll(ITEMS, ctx.entity().items(), ItemTransition.CANCEL, CascadePolicy.FAIL_FAST);
            } catch (TransitionAbortedException swallowed) {
                // the parent tries to carry on as if nothing happened
            }
            return ActionOutcome.ok();
        };
        Order order = new Order("o1", OrderStatus.NEW);
        new Item("i1", ItemStatus.PACKED, Optional.of(order));
        StateMachine<Order, OrderStatus, OrderTransition> orders = registry(10).of(ORDERS);
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                () -> orders.execute(order, OrderTransition.CANCEL));
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
        StateMachine<Order, OrderStatus, OrderTransition> orders = registry(10).of(ORDERS);
        CascadeException error = assertThrows(CascadeException.class, () -> orders.execute(order, OrderTransition.CANCEL));
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
        StateMachine<Order, OrderStatus, OrderTransition> orders = registry(10).of(ORDERS);
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                () -> orders.execute(order, OrderTransition.CANCEL));
        assertTrue(!(error instanceof CascadeException));
    }

    /**
     * Convergence by destination is gone: re-entering an entity that is already in flight is a cycle, even when
     * the nested call asks for the transition that entity is already running.
     */
    @Test
    void reentryOnAnEntityInFlightIsACycleWhicheverIdentifierIsAsked() {
        for (OrderTransition nested : List.of(OrderTransition.SHIP, OrderTransition.CANCEL)) {
            shipOrder.behaviour = (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> {
                ctx.cascade(ITEMS, ctx.entity().items().get(0), ItemTransition.PACK);
                return ActionOutcome.ok();
            };
            packItem.behaviour = (TransitionContext<Item, ItemStatus, ItemTransition> ctx) -> {
                ctx.cascade(ORDERS, ctx.entity().order().orElseThrow(), nested);
                return ActionOutcome.ok();
            };
            Order order = new Order("o1", OrderStatus.PAID);
            Item item = new Item("i1", ItemStatus.PACKING, Optional.of(order));
            StateMachine<Order, OrderStatus, OrderTransition> orders = registry(10).of(ORDERS);
            CascadeCycleException error = assertThrows(CascadeCycleException.class,
                    () -> orders.execute(order, OrderTransition.SHIP));
            assertEquals(List.of("Order/OrderStatus#o1", "Item/ItemStatus#i1", "Order/OrderStatus#o1"), error.route());
            assertEquals(OrderTransition.SHIP, error.inFlightTransition());
            assertEquals(nested, error.requestedTransition());
            assertEquals(OrderStatus.PAID, order.status());
            assertEquals(ItemStatus.PACKING, item.status());
        }
    }

    @Test
    void depthIsBoundedByTheRootMachineSetting() {
        shipOrder.behaviour = (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> {
            ctx.cascade(ITEMS, ctx.entity().items().get(0), ItemTransition.PACK);
            return ActionOutcome.ok();
        };
        packItem.behaviour = (TransitionContext<Item, ItemStatus, ItemTransition> ctx) -> {
            if (ctx.entity().id().equals("i1")) {
                ctx.cascade(ITEMS, ctx.entity().order().orElseThrow().items().get(1), ItemTransition.PACK);
            }
            return ActionOutcome.ok();
        };
        Order order = new Order("o1", OrderStatus.PAID);
        new Item("i1", ItemStatus.PACKING, Optional.of(order));
        new Item("i2", ItemStatus.PACKING, Optional.of(order));
        StateMachine<Order, OrderStatus, OrderTransition> orders = registry(1).of(ORDERS);
        CascadeDepthExceededException error = assertThrows(CascadeDepthExceededException.class,
                () -> orders.execute(order, OrderTransition.SHIP));
        assertEquals(1, error.maxDepth());
        assertEquals(OrderStatus.PAID, order.status());
    }

    @Test
    void peerViewReadsThePendingStateOfTheParent() {
        AtomicReference<OrderStatus> seen = new AtomicReference<>();
        AtomicReference<OrderStatus> raw = new AtomicReference<>();
        shipOrder.behaviour = (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> {
            ctx.cascade(ITEMS, ctx.entity().items().get(0), ItemTransition.PACK);
            return ActionOutcome.ok();
        };
        packItem.behaviour = (TransitionContext<Item, ItemStatus, ItemTransition> ctx) -> {
            Order owner = ctx.entity().order().orElseThrow();
            seen.set(ctx.peer(ORDERS).stateOf(owner));
            raw.set(owner.status());
            return ActionOutcome.ok();
        };
        Order order = new Order("o1", OrderStatus.PAID);
        new Item("i1", ItemStatus.PACKING, Optional.of(order));
        registry(10).of(ORDERS).execute(order, OrderTransition.SHIP);
        assertEquals(OrderStatus.SHIPPED, seen.get());
        assertEquals(OrderStatus.PAID, raw.get());
    }

    @Test
    void twoInstancesWithTheSameIdentityAreAmbiguous() {
        Order first = new Order("o1", OrderStatus.PAID);
        Order second = new Order("o1", OrderStatus.PAID);
        Item item = new Item("i1", ItemStatus.PACKING, Optional.of(second));
        first.add(item);
        shipOrder.behaviour = (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> {
            ctx.cascade(ITEMS, item, ItemTransition.PACK);
            return ActionOutcome.ok();
        };
        packItem.behaviour = (TransitionContext<Item, ItemStatus, ItemTransition> ctx) -> {
            ctx.cascade(ORDERS, second, OrderTransition.SHIP);
            return ActionOutcome.ok();
        };
        StateMachine<Order, OrderStatus, OrderTransition> orders = registry(10).of(ORDERS);
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                () -> orders.execute(first, OrderTransition.SHIP));
        AmbiguousIdentityException ambiguous = assertInstanceOf(AmbiguousIdentityException.class, error.getCause());
        assertEquals("o1", ambiguous.id());
        assertEquals(CallPhase.ACTION, error.phase());
        assertEquals(OrderStatus.PAID, first.status());
    }

    @Test
    void unknownPeerMachineIsAnError() {
        cancelCascades(CascadePolicy.FAIL_FAST);
        Order order = new Order("o1", OrderStatus.NEW);
        new Item("i1", ItemStatus.PENDING, Optional.of(order));
        MachineRegistry onlyOrders = MachineRegistry.builder()
                .add(StateMachines.from(OrderGraphs.orderWithoutWatch(
                                (TransitionContext<Order, OrderStatus, OrderTransition> ctx) ->
                                        CompletableFuture.completedFuture(ActionOutcome.ok()),
                                shipOrder, cancelOrder, retryBudget, allPacked))
                        .state(Order::status, Order::setStatus).identity(Order::id).asyncTimeout(Duration.ofSeconds(5)))
                .build();
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                () -> onlyOrders.of(ORDERS).execute(order, OrderTransition.CANCEL));
        assertInstanceOf(UnknownPeerMachineException.class, error.getCause());
        assertEquals(OrderStatus.NEW, order.status());
    }

    @Test
    void afterACascadeFailureTheUnitOfWorkIsPoisoned() {
        AtomicReference<RuntimeException> later = new AtomicReference<>();
        cancelOrder.behaviour = (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> {
            try {
                ctx.cascadeAll(ITEMS, ctx.entity().items(), ItemTransition.CANCEL, CascadePolicy.FAIL_FAST);
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
        StateMachine<Order, OrderStatus, OrderTransition> orders = registry(10).of(ORDERS);
        assertThrows(TransitionAbortedException.class, () -> orders.execute(order, OrderTransition.CANCEL));
        PoisonedUnitOfWorkException poisoned = assertInstanceOf(PoisonedUnitOfWorkException.class, later.get());
        assertTrue(poisoned.original().getMessage().contains("rejected inside a cascade"));
    }

    @Test
    void cascadeDoesNotInheritRootInput() {
        Object input = new Object();
        Order order = new Order("o", OrderStatus.PAID);
        Item item = new Item("i", ItemStatus.PACKING, Optional.empty());
        packItem.behaviour = (TransitionContext<Item, ItemStatus, ItemTransition> ctx) -> {
            assertTrue(ctx.input().isEmpty());
            return ActionOutcome.ok();
        };
        shipOrder.behaviour = (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> {
            assertSame(input, ctx.input().orElseThrow());
            ctx.cascade(ITEMS, item, ItemTransition.PACK);
            assertSame(input, ctx.input().orElseThrow());
            return ActionOutcome.ok();
        };
        try (MachineRegistry r = registry(8)) {
            assertInstanceOf(Outcome.Applied.class, r.of(ORDERS).execute(order, OrderTransition.SHIP, input));
        }
        assertEquals(ItemStatus.PACKED, item.status());
    }

    @Test
    void cascadeAllDoesNotInheritRootInput() {
        for (CascadePolicy policy : CascadePolicy.values()) {
            Order order = new Order("o", OrderStatus.PAID);
            List<Item> children = List.of(new Item("a", ItemStatus.PACKING, Optional.empty()),
                    new Item("b", ItemStatus.PACKING, Optional.empty()));
            packItem.behaviour = (TransitionContext<Item, ItemStatus, ItemTransition> ctx) -> {
                assertTrue(ctx.input().isEmpty());
                return ActionOutcome.ok();
            };
            shipOrder.behaviour = (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> {
                ctx.cascadeAll(ITEMS, children, ItemTransition.PACK, policy);
                return ActionOutcome.ok();
            };
            try (MachineRegistry r = registry(8)) {
                r.of(ORDERS).execute(order, OrderTransition.SHIP, new Object());
            }
            assertTrue(children.stream().allMatch((Item item) -> item.status() == ItemStatus.PACKED));
        }
    }

    /**
     * COLLECT reverts only the element that failed. A child that an earlier element had already moved keeps
     * that pending state instead of falling back to its committed one, and the journal of the actions whose
     * hops were discarded still travels with the aggregate.
     */
    @Test
    void collectRestoresThePendingStateAnEarlierElementGaveAChild() {
        Order order = new Order("o1", OrderStatus.PAID);
        Item a = new Item("a", ItemStatus.PACKING, Optional.of(order));
        Item b = new Item("b", ItemStatus.PACKING, Optional.of(order));
        Item c = new Item("c", ItemStatus.PACKING, Optional.of(order));
        Item shared = new Item("s", ItemStatus.PENDING, Optional.empty());
        AtomicReference<ItemStatus> seen = new AtomicReference<>();
        shipOrder.behaviour = (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> {
            ctx.cascadeAll(ITEMS, List.of(a, b, c), ItemTransition.PACK, CascadePolicy.COLLECT);
            return ActionOutcome.ok();
        };
        packItem.behaviour = (TransitionContext<Item, ItemStatus, ItemTransition> ctx) -> {
            switch (ctx.entity().id()) {
                case "a" -> ctx.cascade(ITEMS, shared, ItemTransition.START_PACKING);
                case "b" -> {
                    ctx.cascade(ITEMS, shared, ItemTransition.PACK);
                    ctx.cascade(ITEMS, shared, ItemTransition.PACK);
                }
                case "c" -> seen.set(ctx.peer(ITEMS).stateOf(shared));
                default -> { }
            }
            return ActionOutcome.ok();
        };
        StateMachine<Order, OrderStatus, OrderTransition> orders = registry(10).of(ORDERS);
        CascadeException error = assertThrows(CascadeException.class, () -> orders.execute(order, OrderTransition.SHIP));
        assertEquals(ItemStatus.PACKING, seen.get());
        assertEquals(1, error.errors().size());
        assertEquals(List.of("ShipOrder:Order#o1:THREW", "PackItem:Item#a:OK", "PackItem:Item#b:THREW",
                        "PackItem:Item#s:OK", "PackItem:Item#c:OK"),
                error.journal().stream()
                        .map((ActionRecord record) -> record.action() + ":" + record.entity() + ":" + record.status())
                        .toList());
        assertEquals(OrderStatus.PAID, order.status());
        assertEquals(ItemStatus.PENDING, shared.status());
        assertTrue(List.of(a, b, c).stream().allMatch((Item item) -> item.status() == ItemStatus.PACKING));
    }
}
