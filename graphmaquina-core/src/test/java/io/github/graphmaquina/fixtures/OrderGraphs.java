package io.github.graphmaquina.fixtures;

import static io.github.graphmaquina.api.WatchCondition.allIn;
import static io.github.graphmaquina.fixtures.Keys.ITEMS;
import static io.github.graphmaquina.fixtures.Keys.ORDERS;

import io.github.graphmaquina.api.AsyncTransitionAction;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.api.TransitionGuard;
import io.github.graphmaquina.dsl.GraphBuilder;
import io.github.graphmaquina.dsl.StateGraph;
import java.io.IOException;
import java.time.Duration;

public final class OrderGraphs {

    private OrderGraphs() { }

    public static GraphDefinition<Order, OrderStatus, OrderTransition> order(
            AsyncTransitionAction<Order, OrderStatus, OrderTransition> chargeCard,
            TransitionAction<Order, OrderStatus, OrderTransition> shipOrder,
            TransitionAction<Order, OrderStatus, OrderTransition> cancelOrder,
            TransitionGuard<Order, OrderStatus, OrderTransition> retryBudgetLeft,
            TransitionGuard<Order, OrderStatus, OrderTransition> allItemsPacked) {
        return orderHead(chargeCard, shipOrder, cancelOrder, retryBudgetLeft, allItemsPacked)
                .watch(OrderStatus.PAID, ITEMS, Order::items, Item::order)
                    .when(allIn(ItemStatus.PACKED)).execute(OrderTransition.SHIP)
                    .endWatch()
                .build();
    }

    /** Same machine without the watch on PAID: a registry with only Order must still build. */
    public static GraphDefinition<Order, OrderStatus, OrderTransition> orderWithoutWatch(
            AsyncTransitionAction<Order, OrderStatus, OrderTransition> chargeCard,
            TransitionAction<Order, OrderStatus, OrderTransition> shipOrder,
            TransitionAction<Order, OrderStatus, OrderTransition> cancelOrder,
            TransitionGuard<Order, OrderStatus, OrderTransition> retryBudgetLeft,
            TransitionGuard<Order, OrderStatus, OrderTransition> allItemsPacked) {
        return orderHead(chargeCard, shipOrder, cancelOrder, retryBudgetLeft, allItemsPacked).build();
    }

    private static GraphBuilder<Order, OrderStatus, OrderTransition> orderHead(
            AsyncTransitionAction<Order, OrderStatus, OrderTransition> chargeCard,
            TransitionAction<Order, OrderStatus, OrderTransition> shipOrder,
            TransitionAction<Order, OrderStatus, OrderTransition> cancelOrder,
            TransitionGuard<Order, OrderStatus, OrderTransition> retryBudgetLeft,
            TransitionGuard<Order, OrderStatus, OrderTransition> allItemsPacked) {
        return StateGraph.define(ORDERS)
                .initial(OrderStatus.NEW)
                .terminal(OrderStatus.SHIPPED)
                .terminal(OrderStatus.CANCELLED)
                .allowsCycle(OrderStatus.PAYMENT_DECLINED)
                .statePolicy(OrderStatus.NEW, "cannot ship without payment")
                .statePolicy(OrderStatus.PAID, "money was charged: requires a refund")
                .statePolicy(OrderStatus.PAYMENT_DECLINED, "go back to NEW to retry the charge")
                .transition(OrderTransition.CHARGE, OrderStatus.NEW, OrderStatus.PAID)
                    .asyncAction("ChargeCard", chargeCard).timeout(Duration.ofSeconds(5))
                    .guard("RetryBudgetLeft", retryBudgetLeft)
                    .onFailure(OrderTransition.DECLINE).failsOn(IOException.class)
                    .endTransition()
                .transition(OrderTransition.DECLINE, OrderStatus.NEW, OrderStatus.PAYMENT_DECLINED).endTransition()
                .transition(OrderTransition.CANCEL, OrderStatus.NEW, OrderStatus.CANCELLED)
                    .action("CancelOrder", cancelOrder).endTransition()
                .transition(OrderTransition.SHIP, OrderStatus.PAID, OrderStatus.SHIPPED)
                    .action("ShipOrder", shipOrder).guard("AllItemsPacked", allItemsPacked).endTransition()
                .transition(OrderTransition.RETRY, OrderStatus.PAYMENT_DECLINED, OrderStatus.NEW)
                    .guard("RetryBudgetLeft", retryBudgetLeft).endTransition()
                .transition(OrderTransition.CANCEL, OrderStatus.PAYMENT_DECLINED, OrderStatus.CANCELLED)
                    .action("CancelOrder", cancelOrder).endTransition();
    }

    public static GraphDefinition<Item, ItemStatus, ItemTransition> item(
            TransitionAction<Item, ItemStatus, ItemTransition> packItem) {
        return StateGraph.define(ITEMS)
                .initial(ItemStatus.PENDING)
                .terminal(ItemStatus.PACKED)
                .terminal(ItemStatus.CANCELLED)
                .statePolicy(ItemStatus.PENDING, "an item is packed by going through PACKING")
                .statePolicy(ItemStatus.PACKING, "cannot go back to pending")
                .transition(ItemTransition.START_PACKING, ItemStatus.PENDING, ItemStatus.PACKING).endTransition()
                .transition(ItemTransition.CANCEL, ItemStatus.PENDING, ItemStatus.CANCELLED).endTransition()
                .transition(ItemTransition.PACK, ItemStatus.PACKING, ItemStatus.PACKED)
                    .action("PackItem", packItem).endTransition()
                .transition(ItemTransition.CANCEL, ItemStatus.PACKING, ItemStatus.CANCELLED).endTransition()
                .build();
    }
}
