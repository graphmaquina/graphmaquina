package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.Keys.ITEMS;
import static io.github.graphmaquina.fixtures.Keys.ORDERS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.Reaction;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.TransitionListener;
import io.github.graphmaquina.error.AfterCommitException;
import io.github.graphmaquina.fixtures.Item;
import io.github.graphmaquina.fixtures.ItemStatus;
import io.github.graphmaquina.fixtures.ItemTransition;
import io.github.graphmaquina.fixtures.Order;
import io.github.graphmaquina.fixtures.OrderGraphs;
import io.github.graphmaquina.fixtures.OrderStatus;
import io.github.graphmaquina.fixtures.OrderTransition;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * The registry's transition listener: it sees the outcome of every call once, a reaction's outcome reaches it in a call
 * of its own before the outcome of the root call that triggered the reaction, a listener failure surfaces as an
 * AfterCommitException from a root call and as an aborted reaction inside a reaction, and a registry takes one
 * listener only.
 */
class ListenerTest {

    final List<String> seen = new ArrayList<>();
    final TransitionListener listener = (MachineKey<?, ?, ?> key, Outcome<?, ?, ?> outcome) ->
            seen.add(key.entity().getSimpleName() + ":" + outcome.status().name() + ":" + outcome.from()
                    + ">" + outcome.recordedState());

    private MachineRegistry registry(TransitionListener listener) {
        return MachineRegistry.builder()
                .add(StateMachines.from(OrderGraphs.order(
                                (TransitionContext<Order, OrderStatus, OrderTransition> ctx) ->
                                        CompletableFuture.completedFuture(ActionOutcome.ok()),
                                (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> ActionOutcome.ok(),
                                (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> ActionOutcome.ok(),
                                (GuardContext<Order, OrderStatus, OrderTransition> ctx) -> GuardResult.pass(),
                                (GuardContext<Order, OrderStatus, OrderTransition> ctx) -> GuardResult.pass()))
                        .state(Order::status, Order::setStatus).identity(Order::id).asyncTimeout(Duration.ofSeconds(5)))
                .add(StateMachines.from(OrderGraphs.item(
                                (TransitionContext<Item, ItemStatus, ItemTransition> ctx) -> ActionOutcome.ok()))
                        .state(Item::status, Item::setStatus).identity(Item::id))
                .listener(listener)
                .build();
    }

    @Test
    void everyOutcomeKindReachesTheListenerOncePerCall() {
        try (MachineRegistry registry = registry(listener)) {
            StateMachine<Order, OrderStatus, OrderTransition> orders = registry.of(ORDERS);
            Order order = new Order("o1", OrderStatus.NEW);
            orders.execute(order, OrderTransition.CHARGE);        // Applied
            orders.execute(order, OrderTransition.CHARGE);        // Rejected: no CHARGE leaves PAID
            orders.reevaluate(order);                             // Ignored: no item is packed
            assertEquals(List.of("Order:APPLIED:NEW>PAID", "Order:REJECTED:PAID>PAID", "Order:IGNORED:PAID>PAID"),
                    seen);
        }
    }

    @Test
    void reactionsArriveInTheirOwnCallBeforeTheRootOutcome() {
        try (MachineRegistry registry = registry(listener)) {
            Order order = new Order("o1", OrderStatus.PAID);
            Item item = new Item("i1", ItemStatus.PACKING, Optional.of(order));
            Outcome<Item, ItemStatus, ItemTransition> outcome = registry.of(ITEMS).execute(item, ItemTransition.PACK);
            assertInstanceOf(Outcome.Applied.class, outcome);
            Outcome.Applied<Item, ItemStatus, ItemTransition> applied =
                    (Outcome.Applied<Item, ItemStatus, ItemTransition>) outcome;
            assertInstanceOf(Reaction.Done.class, applied.reactions().get(0));
            assertEquals(List.of("Order:APPLIED:PAID>SHIPPED", "Item:APPLIED:PACKING>PACKED"), seen);
        }
    }

    @Test
    void listenerFailureIsAnAfterCommitExceptionAtTheRootAndAnAbortedReactionInsideAReaction() {
        IllegalStateException boom = new IllegalStateException("audit down");
        List<String> calls = new ArrayList<>();
        TransitionListener failing = (MachineKey<?, ?, ?> key, Outcome<?, ?, ?> outcome) -> {
            calls.add(key.entity().getSimpleName());
            if (key.equals(ORDERS)) {
                throw boom;
            }
        };
        try (MachineRegistry registry = registry(failing)) {
            Order order = new Order("o1", OrderStatus.NEW);
            AfterCommitException error = assertThrows(AfterCommitException.class,
                    () -> registry.of(ORDERS).execute(order, OrderTransition.CHARGE));
            assertSame(boom, error.getCause());
            assertEquals("listener", error.step());
            assertEquals(OrderStatus.PAID, order.status());

            Item item = new Item("i1", ItemStatus.PACKING, Optional.of(order));
            Outcome<Item, ItemStatus, ItemTransition> outcome = registry.of(ITEMS).execute(item, ItemTransition.PACK);
            assertInstanceOf(Outcome.Applied.class, outcome);
            Outcome.Applied<Item, ItemStatus, ItemTransition> applied =
                    (Outcome.Applied<Item, ItemStatus, ItemTransition>) outcome;
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
