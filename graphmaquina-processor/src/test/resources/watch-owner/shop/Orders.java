package shop;

import io.github.graphmaquina.annotation.GenerateGraphApi;
import io.github.graphmaquina.annotation.GraphPeers;
import io.github.graphmaquina.api.Machine;
import io.github.graphmaquina.api.Outcome;
import static shop.OrderStatus.*;
import static shop.OrderTransition.*;

/** Orders: a paid order ships once all its items are packed; the tests vary how its watch finds an item's order. */
@GenerateGraphApi
@GraphPeers(Items.class)
@OrdersGraph.Definition(initial = NEW, terminals = SHIPPED,
    state = OrderRefs.Status.class, id = OrderRefs.Id.class)
@OrdersGraph.StatePolicy(state = NEW, otherwise = "an order is paid first")
@OrdersGraph.StatePolicy(state = PAID, otherwise = "a paid order waits for its items")
@OrdersGraph.Transition(id = SHIP, from = PAID, to = SHIPPED)
@OrdersItems.Watch(state = PAID, peers = OrderRefs.Items.class,
    ownerLookup = OrderLookupsRefs.OrderOf.class,
    rules = @OrdersItems.When(allIn = ItemStatus.PACKED, transition = SHIP))
public interface Orders extends Machine<Order, OrderStatus, OrderTransition> {
    @OrdersGraph.Transition(id = PAY, from = NEW, to = PAID)
    Outcome<Order, OrderStatus, OrderTransition> pay(Order order);
}
