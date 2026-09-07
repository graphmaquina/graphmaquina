package io.stategraph.fixtures;

import static io.stategraph.api.WatchCondition.allIn;
import static io.stategraph.fixtures.Keys.ITEMS;
import static io.stategraph.fixtures.Keys.ORDERS;

import io.stategraph.api.AsyncTransitionAction;
import io.stategraph.api.GraphDefinition;
import io.stategraph.api.TransitionAction;
import io.stategraph.api.TransitionGuard;
import io.stategraph.dsl.StateGraph;
import java.io.IOException;
import java.time.Duration;

public final class OrderGraphs {

    private OrderGraphs() { }

    public static GraphDefinition<Order, OrderStatus> order(AsyncTransitionAction<Order, OrderStatus> chargeCard,
                                                            TransitionAction<Order, OrderStatus> shipOrder,
                                                            TransitionAction<Order, OrderStatus> cancelOrder,
                                                            TransitionGuard<Order, OrderStatus> retryBudgetLeft,
                                                            TransitionGuard<Order, OrderStatus> allItemsPacked) {
        return StateGraph.define(ORDERS)
                .initial(OrderStatus.NEW)
                .terminal(OrderStatus.SHIPPED)
                .terminal(OrderStatus.CANCELLED)
                .allowsCycle(OrderStatus.PAYMENT_DECLINED)
                .from(OrderStatus.NEW)
                    .to(OrderStatus.PAID).asyncAction("ChargeCard", chargeCard).timeout(Duration.ofSeconds(5))
                        .guard("RetryBudgetLeft", retryBudgetLeft)
                        .onFailure(OrderStatus.PAYMENT_DECLINED).failsOn(IOException.class)
                    .to(OrderStatus.CANCELLED).action("CancelOrder", cancelOrder)
                    .to(OrderStatus.PAYMENT_DECLINED).move()
                    .stay()
                    .forbidOthers("cannot ship without payment")
                .from(OrderStatus.PAID)
                    .to(OrderStatus.SHIPPED).action("ShipOrder", shipOrder).guard("AllItemsPacked", allItemsPacked)
                    .stay()
                    .watches(ITEMS, Order::items, Item::order)
                        .when(allIn(ItemStatus.PACKED)).goTo(OrderStatus.SHIPPED)
                    .forbidOthers("money was charged: requires a refund")
                .from(OrderStatus.PAYMENT_DECLINED)
                    .to(OrderStatus.NEW).move().guard("RetryBudgetLeft", retryBudgetLeft)
                    .to(OrderStatus.CANCELLED).action("CancelOrder", cancelOrder)
                    .stay()
                    .forbidOthers("go back to NEW to retry the charge")
                .from(OrderStatus.SHIPPED).stay().forbidOthers("a shipment cannot be undone")
                .from(OrderStatus.CANCELLED).stay().forbidOthers("cancelled is final")
                .build();
    }

    public static GraphDefinition<Item, ItemStatus> item(TransitionAction<Item, ItemStatus> packItem) {
        return StateGraph.define(ITEMS)
                .initial(ItemStatus.PENDING)
                .terminal(ItemStatus.PACKED)
                .terminal(ItemStatus.CANCELLED)
                .from(ItemStatus.PENDING).to(ItemStatus.PACKING).move().to(ItemStatus.CANCELLED).move().stay()
                    .forbidOthers("an item is packed by going through PACKING")
                .from(ItemStatus.PACKING).to(ItemStatus.PACKED).action("PackItem", packItem).to(ItemStatus.CANCELLED).move().stay()
                    .forbidOthers("cannot go back to pending")
                .from(ItemStatus.PACKED).stay().forbidOthers("packed is final")
                .from(ItemStatus.CANCELLED).stay().forbidOthers("cancelled is final")
                .build();
    }
}
