package io.github.graphmaquina.example;

import io.github.graphmaquina.example.order.Carrier;
import io.github.graphmaquina.example.order.Order;
import io.github.graphmaquina.example.order.OrderStatus;
import java.util.ArrayList;
import java.util.List;

/**
 * Records one entry per shipment: the order id, the state the order had when the carrier was told, which is SHIPPED
 * because the hook runs after the commit, and {@code :closed} when the shipment ended the order's flow.
 */
final class FakeCarrier implements Carrier {

    final List<String> notified = new ArrayList<>();

    @Override
    public void shipped(Order order, boolean orderClosed) {
        OrderStatus status = order.status();
        notified.add(order.id() + ":" + status + (orderClosed ? ":closed" : ""));
    }
}
