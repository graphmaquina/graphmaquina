package io.github.graphmaquina.example.order;

import static io.github.graphmaquina.example.order.OrderStatus.CANCELLED;
import static io.github.graphmaquina.example.order.OrderStatus.NEW;
import static io.github.graphmaquina.example.order.OrderStatus.PAID;
import static io.github.graphmaquina.example.order.OrderStatus.PAYMENT_DECLINED;
import static io.github.graphmaquina.example.order.OrderStatus.SHIPPED;

import io.github.graphmaquina.annotation.GenerateGraphApi;
import io.github.graphmaquina.annotation.GraphPeers;
import io.github.graphmaquina.annotation.Timeout;
import io.github.graphmaquina.api.Machine;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.example.item.ItemRefs;
import io.github.graphmaquina.example.item.ItemStatus;
import io.github.graphmaquina.example.item.Items;
import java.io.IOException;

/**
 * The order machine: five states and six edges, all declared here. The processor generates OrdersRuntime.
 *
 * <p>States are static-imported and identifiers are written out, because {@code PAYMENT_DECLINED} names both a
 * state and the recovery edge that reaches it.</p>
 */
@GenerateGraphApi
@GraphPeers(Items.class)
@OrdersGraph.Definition(initial = NEW, terminals = {SHIPPED, CANCELLED}, allowsCycle = PAYMENT_DECLINED,
        state = OrderRefs.Status.class, id = OrderRefs.Id.class)
@OrdersGraph.StatePolicy(state = NEW,              otherwise = "cannot ship without payment")
@OrdersGraph.StatePolicy(state = PAID,             otherwise = "money was charged: requires a refund")
@OrdersGraph.StatePolicy(state = PAYMENT_DECLINED, otherwise = "go back to NEW to retry the charge")
@OrdersGraph.StatePolicy(state = SHIPPED,          otherwise = "a shipment cannot be undone")
@OrdersGraph.StatePolicy(state = CANCELLED,        otherwise = "cancelled is final")
@OrdersGraph.Transition(id = OrderTransition.PAYMENT_DECLINED, from = NEW, to = PAYMENT_DECLINED)
@OrdersItems.Watch(state = PAID, peers = OrderRefs.Items.class, owner = ItemRefs.Order.class,
        rules = @OrdersItems.When(allIn = ItemStatus.PACKED, transition = OrderTransition.SHIP))
public interface Orders extends Machine<Order, OrderStatus, OrderTransition> {

    @OrdersGraph.Transition(id = OrderTransition.PAY, from = NEW, to = PAID,
        action = OrderActionsRefs.Charge.class,
        guards = OrderActionsRefs.RetryBudgetLeft.class,
        onFailure = OrderTransition.PAYMENT_DECLINED, failsOn = IOException.class,
        timeout = @Timeout(seconds = 5))
    Outcome<Order, OrderStatus, OrderTransition> pay(Order order);

    @OrdersGraph.Transition(id = OrderTransition.RETRY, from = PAYMENT_DECLINED, to = NEW,
            guards = OrderActionsRefs.RetryBudgetLeft.class)
    Outcome<Order, OrderStatus, OrderTransition> retry(Order order);

    @OrdersGraph.Transition(id = OrderTransition.SHIP, from = PAID, to = SHIPPED,
            action = OrderActionsRefs.Ship.class,
            guards = OrderActionsRefs.AllItemsPacked.class, afterCommit = OrderActionsRefs.NotifyCarrier.class)
    Outcome<Order, OrderStatus, OrderTransition> ship(Order order);

    @OrdersGraph.Transition(id = OrderTransition.CANCEL, from = {NEW, PAYMENT_DECLINED}, to = CANCELLED,
            action = OrderActionsRefs.Cancel.class)
    Outcome<Order, OrderStatus, OrderTransition> cancel(Order order, String cancellationId);
}
