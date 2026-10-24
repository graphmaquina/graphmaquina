package io.github.graphmaquina.fixtures;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;

/** Test entity of the order machine: an id, a status, its items and a count of payment attempts. */
@Getter
public final class Order {

    private final String id;
    private OrderStatus status;
    private final List<Item> items = new ArrayList<>();
    private int paymentAttempts;

    public Order(String id, OrderStatus status) {
        this.id = id;
        this.status = status;
    }

    public void setStatus(OrderStatus status) { this.status = status; }

    public void add(Item item) { items.add(item); }

    public void countAttempt() { paymentAttempts++; }

    @Override
    public String toString() { return "Order#" + id; }
}
