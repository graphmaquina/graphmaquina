package io.stategraph.example.order;

import io.stategraph.annotation.Action;
import io.stategraph.annotation.Cell;
import io.stategraph.annotation.Move;
import io.stategraph.annotation.Row;
import io.stategraph.annotation.StateGraph;
import io.stategraph.annotation.Watch;
import io.stategraph.annotation.When;
import io.stategraph.api.Machine;
import io.stategraph.api.Outcome;
import io.stategraph.example.item.Items;
import java.io.IOException;

/** The order machine: five states, twenty-five cells, all declared here. The processor generates Orders_. */
@StateGraph(initial = "NEW", terminals = {"SHIPPED", "CANCELLED"}, allowsCycle = "PAYMENT_DECLINED")
@Row(state = "NEW",              otherwise = "cannot ship without payment")
@Row(state = "PAID",             otherwise = "money was charged: requires a refund")
@Row(state = "PAYMENT_DECLINED", otherwise = "go back to NEW to retry the charge")
@Row(state = "SHIPPED",          otherwise = "a shipment cannot be undone")
@Row(state = "CANCELLED",        otherwise = "cancelled is final")
@Cell(from = "NEW", to = "PAYMENT_DECLINED")
@Watch(state = "PAID", peer = Items.class, peers = "items", owner = "order", rules = @When(allIn = "PACKED", goTo = "SHIPPED"))
public interface Orders extends Machine<Order, OrderStatus> {

    @Action(from = "NEW", to = "PAID", by = OrderActions.class, action = "charge", guards = "retryBudgetLeft",
            onFailure = "PAYMENT_DECLINED", failsOn = IOException.class, timeout = "PT5S")
    Outcome<Order, OrderStatus> pay(Order order);

    @Move(from = "PAYMENT_DECLINED", to = "NEW", by = OrderActions.class, guards = "retryBudgetLeft")
    Outcome<Order, OrderStatus> retry(Order order);

    @Action(from = "PAID", to = "SHIPPED", by = OrderActions.class, guards = "allItemsPacked", afterCommit = "notifyCarrier")
    Outcome<Order, OrderStatus> ship(Order order);

    @Action(from = {"NEW", "PAYMENT_DECLINED"}, to = "CANCELLED", by = OrderActions.class)
    Outcome<Order, OrderStatus> cancel(Order order);
}
