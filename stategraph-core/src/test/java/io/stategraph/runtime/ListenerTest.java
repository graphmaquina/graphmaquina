package io.stategraph.runtime;

import static io.stategraph.fixtures.Keys.ITEMS;
import static io.stategraph.fixtures.Keys.ORDERS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.stategraph.api.ActionOutcome;
import io.stategraph.api.GuardContext;
import io.stategraph.api.GuardResult;
import io.stategraph.api.MachineKey;
import io.stategraph.api.Outcome;
import io.stategraph.api.Reaction;
import io.stategraph.api.StateMachine;
import io.stategraph.api.TransitionContext;
import io.stategraph.api.TransitionListener;
import io.stategraph.error.AfterCommitException;
import io.stategraph.fixtures.Item;
import io.stategraph.fixtures.ItemStatus;
import io.stategraph.fixtures.Order;
import io.stategraph.fixtures.OrderGraphs;
import io.stategraph.fixtures.OrderStatus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class ListenerTest {

    final List<String> seen = new ArrayList<>();
    final TransitionListener listener = (MachineKey<?, ?> key, Outcome<?, ?> outcome) ->
            seen.add(key.entity().getSimpleName() + ":" + outcome.getClass().getSimpleName() + ":" + outcome.from() + ">" + outcome.current());

    private MachineRegistry registry(TransitionListener listener) {
        return MachineRegistry.builder()
                .add(StateMachines.from(OrderGraphs.order(
                                (TransitionContext<Order, OrderStatus> ctx) -> CompletableFuture.completedFuture(ActionOutcome.ok()),
                                (TransitionContext<Order, OrderStatus> ctx) -> ActionOutcome.ok(),
                                (TransitionContext<Order, OrderStatus> ctx) -> ActionOutcome.ok(),
                                (GuardContext<Order, OrderStatus> ctx) -> GuardResult.pass(),
                                (GuardContext<Order, OrderStatus> ctx) -> GuardResult.pass()))
                        .state(Order::status, Order::setStatus).identity(Order::id).asyncTimeout(Duration.ofSeconds(5)))
                .add(StateMachines.from(OrderGraphs.item((TransitionContext<Item, ItemStatus> ctx) -> ActionOutcome.ok()))
                        .state(Item::status, Item::setStatus).identity(Item::id))
                .listener(listener)
                .build();
    }

    @Test
    void everyOutcomeKindReachesTheListenerOncePerCall() {
        try (MachineRegistry registry = registry(listener)) {
            StateMachine<Order, OrderStatus> orders = registry.of(ORDERS);
            Order order = new Order("o1", OrderStatus.NEW);
            orders.transitionTo(order, OrderStatus.PAID);          // Applied
            orders.transitionTo(order, OrderStatus.PAID);          // Ignored (stay)
            orders.transitionTo(order, OrderStatus.NEW);           // Rejected (forbid)
            orders.reevaluate(order);                              // Ignored without a transaction: no items packed
            assertEquals(List.of("Order:Applied:NEW>PAID", "Order:Ignored:PAID>PAID", "Order:Rejected:PAID>PAID",
                    "Order:Ignored:PAID>PAID"), seen);
        }
    }

    @Test
    void reactionsArriveInTheirOwnCallAfterTheRootCall() {
        try (MachineRegistry registry = registry(listener)) {
            Order order = new Order("o1", OrderStatus.PAID);
            Item item = new Item("i1", ItemStatus.PACKING, Optional.of(order));
            Outcome<Item, ItemStatus> outcome = registry.of(ITEMS).transitionTo(item, ItemStatus.PACKED);
            assertInstanceOf(Outcome.Applied.class, outcome);
            Outcome.Applied<Item, ItemStatus> applied = (Outcome.Applied<Item, ItemStatus>) outcome;
            assertInstanceOf(Reaction.Done.class, applied.reactions().get(0));
            assertEquals(List.of("Order:Applied:PAID>SHIPPED", "Item:Applied:PACKING>PACKED"), seen);
        }
    }

    @Test
    void listenerFailureIsAnAfterCommitExceptionAtTheRootAndAnAbortedReactionInsideAReaction() {
        IllegalStateException boom = new IllegalStateException("audit down");
        List<String> calls = new ArrayList<>();
        TransitionListener failing = (MachineKey<?, ?> key, Outcome<?, ?> outcome) -> {
            calls.add(key.entity().getSimpleName());
            if (key.equals(ORDERS)) {
                throw boom;
            }
        };
        try (MachineRegistry registry = registry(failing)) {
            Order order = new Order("o1", OrderStatus.NEW);
            AfterCommitException error = assertThrows(AfterCommitException.class,
                    () -> registry.of(ORDERS).transitionTo(order, OrderStatus.PAID));
            assertSame(boom, error.getCause());
            assertEquals("listener", error.step());
            assertEquals(OrderStatus.PAID, order.status());

            Item item = new Item("i1", ItemStatus.PACKING, Optional.of(order));
            Outcome<Item, ItemStatus> outcome = registry.of(ITEMS).transitionTo(item, ItemStatus.PACKED);
            assertInstanceOf(Outcome.Applied.class, outcome);
            Outcome.Applied<Item, ItemStatus> applied = (Outcome.Applied<Item, ItemStatus>) outcome;
            Reaction.Aborted aborted = assertInstanceOf(Reaction.Aborted.class, applied.reactions().get(0));
            assertInstanceOf(AfterCommitException.class, aborted.cause());
            assertEquals(OrderStatus.SHIPPED, order.status());
            assertEquals(List.of("Order", "Order", "Item"), calls);
        }
    }

    @Test
    void listenerCanOnlyBeSetOnce() {
        MachineRegistry.Builder builder = MachineRegistry.builder().listener(listener);
        assertThrows(IllegalStateException.class, () -> builder.listener(listener));
    }
}
