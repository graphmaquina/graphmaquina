package io.stategraph.example.order;

import static io.stategraph.api.WatchCondition.allIn;
import static io.stategraph.example.order.OrderStatus.CANCELLED;
import static io.stategraph.example.order.OrderStatus.NEW;
import static io.stategraph.example.order.OrderStatus.PAID;
import static io.stategraph.example.order.OrderStatus.PAYMENT_DECLINED;
import static io.stategraph.example.order.OrderStatus.SHIPPED;

import io.stategraph.api.GraphDefinition;
import io.stategraph.dsl.StateGraph;
import io.stategraph.example.Machines;
import io.stategraph.example.item.Item;
import io.stategraph.example.item.ItemStatus;
import java.io.IOException;
import java.time.Duration;

/** The graph from spec section 5.1: five states, twenty-five cells, all declared. */
public final class OrderGraph {

    private OrderGraph() { }

    public static GraphDefinition<Order, OrderStatus> define(ChargeCard chargeCard, ShipOrder shipOrder, CancelOrder cancelOrder,
                                                             RetryBudgetLeft retryBudgetLeft, AllItemsPacked allItemsPacked) {
        return StateGraph.define(Machines.ORDERS)
                .initial(NEW)
                .terminal(SHIPPED)
                .terminal(CANCELLED)
                .allowsCycle(PAYMENT_DECLINED)

                .from(NEW)
                    .to(PAID).asyncAction(chargeCard).timeout(Duration.ofSeconds(5))
                             .guard(retryBudgetLeft)
                             .onFailure(PAYMENT_DECLINED).failsOn(IOException.class)
                    .to(CANCELLED).action(cancelOrder)
                    .to(PAYMENT_DECLINED).move()
                    .stay()
                    .forbidOthers("cannot ship without payment")

                .from(PAID)
                    .to(SHIPPED).action(shipOrder).guard(allItemsPacked)
                    .stay()
                    .watches(Machines.ITEMS, Order::items, Item::order)
                        .when(allIn(ItemStatus.PACKED)).goTo(SHIPPED)
                    .forbidOthers("money was charged: requires a refund")

                .from(PAYMENT_DECLINED)
                    .to(NEW).move().guard(retryBudgetLeft)
                    .to(CANCELLED).action(cancelOrder)
                    .stay()
                    .forbidOthers("go back to NEW to retry the charge")

                .from(SHIPPED).stay().forbidOthers("a shipment cannot be undone")
                .from(CANCELLED).stay().forbidOthers("cancelled is final")
                .build();
    }
}
