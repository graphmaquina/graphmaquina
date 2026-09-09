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

/** The order machine: five states, twenty-five cells, all declared here. The processor generates Orders_. */
@GenerateGraphApi
@GraphPeers(Items.class)
@OrdersGraph.Definition(initial = NEW, terminals = {SHIPPED, CANCELLED}, allowsCycle = PAYMENT_DECLINED,
        state = OrderRefs.Status.class, id = OrderRefs.Id.class)
@OrdersGraph.Row(state = NEW,              otherwise = "cannot ship without payment")
@OrdersGraph.Row(state = PAID,             otherwise = "money was charged: requires a refund")
@OrdersGraph.Row(state = PAYMENT_DECLINED, otherwise = "go back to NEW to retry the charge")
@OrdersGraph.Row(state = SHIPPED,          otherwise = "a shipment cannot be undone")
@OrdersGraph.Row(state = CANCELLED,        otherwise = "cancelled is final")
@OrdersGraph.Cell(from = NEW, to = PAYMENT_DECLINED)
@OrdersItems.Watch(state = PAID, peers = OrderRefs.Items.class, owner = ItemRefs.Order.class,
        rules = @OrdersItems.When(allIn = ItemStatus.PACKED, goTo = SHIPPED))
public interface Orders extends Machine<Order, OrderStatus> {

    @OrdersGraph.Action(from = NEW, to = PAID,
        action = OrderActionsRefs.Charge.class,
        guards = OrderActionsRefs.RetryBudgetLeft.class,
        onFailure = PAYMENT_DECLINED, failsOn = IOException.class,
        timeout = @Timeout(seconds = 5))
    Outcome<Order, OrderStatus> pay(Order order);

    @OrdersGraph.Move(from = PAYMENT_DECLINED, to = NEW, guards = OrderActionsRefs.RetryBudgetLeft.class)
    Outcome<Order, OrderStatus> retry(Order order);

    @OrdersGraph.Action(from = PAID, to = SHIPPED, action = OrderActionsRefs.Ship.class,
            guards = OrderActionsRefs.AllItemsPacked.class, afterCommit = OrderActionsRefs.NotifyCarrier.class)
    Outcome<Order, OrderStatus> ship(Order order);

    @OrdersGraph.Action(from = {NEW, PAYMENT_DECLINED}, to = CANCELLED, action = OrderActionsRefs.Cancel.class)
    Outcome<Order, OrderStatus> cancel(Order order);
}
