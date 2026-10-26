package shop;

import static shop.OrderStatus.NEW;
import static shop.OrderStatus.PAID;
import static shop.OrderStatus.SHIPPED;
import static shop.OrderTransition.PAY;
import static shop.OrderTransition.SHIP;

import io.github.graphmaquina.annotation.GenerateGraphApi;
import io.github.graphmaquina.annotation.GraphPeers;
import io.github.graphmaquina.api.Machine;
import io.github.graphmaquina.api.Outcome;
import stock.ItemStatus;
import stock.Items;

/**
 * The order machine of the consumer: a paid order watches its items, items of {@link Items}, a machine of the
 * producer's jar, and ships once all of them are packed. The watch is declared here, on the owner's side, and finds
 * the order of an item through a lookup of the consumer: the producer knows no order.
 */
@GenerateGraphApi
@GraphPeers(Items.class)
@OrdersGraph.Definition(
    initial = NEW,
    terminals = SHIPPED,
    state = OrderRefs.Status.class,
    id = OrderRefs.Id.class)
@OrdersGraph.StatePolicy(state = NEW, otherwise = "an order is paid first")
@OrdersGraph.StatePolicy(state = PAID, otherwise = "a paid order waits for its items")
@OrdersGraph.Transition(id = SHIP, from = PAID, to = SHIPPED)
@OrdersItems.Watch(
    state = PAID,
    peers = OrderRefs.Items.class,
    ownerLookup = OrderLookupsRefs.OrderOf.class,
    rules = @OrdersItems.When(allIn = ItemStatus.PACKED, transition = SHIP))
public interface Orders extends Machine<Order, OrderStatus, OrderTransition> {

    @OrdersGraph.Transition(id = PAY, from = NEW, to = PAID)
    Outcome<Order, OrderStatus, OrderTransition> pay(Order order);
}
