package io.stategraph.example;

import io.stategraph.example.order.Carrier;
import io.stategraph.example.order.Order;
import io.stategraph.example.order.OrderStatus;
import java.util.ArrayList;
import java.util.List;

/** Records the state the order had when the carrier was told: SHIPPED, because the hook runs after the commit. */
final class FakeCarrier implements Carrier {

    final List<String> notified = new ArrayList<>();

    @Override
    public void shipped(Order order) {
        OrderStatus status = order.status();
        notified.add(order.id() + ":" + status);
    }
}
