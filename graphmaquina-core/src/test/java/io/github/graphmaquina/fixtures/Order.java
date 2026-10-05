package io.github.graphmaquina.fixtures;

import java.util.ArrayList;
import java.util.List;

/** Test entity of the order machine: an id, a status, its items and a count of payment attempts. */
public final class Order {

    private final String id;
    private OrderStatus status;
    private final List<Item> items = new ArrayList<>();
    private int paymentAttempts;

    public Order(String id, OrderStatus status) {
        this.id = id;
        this.status = status;
    }

    public String id() { return id; }

    public OrderStatus status() { return status; }

    public void setStatus(OrderStatus status) { this.status = status; }

    public List<Item> items() { return items; }

    public void add(Item item) { items.add(item); }

    public int paymentAttempts() { return paymentAttempts; }

    public void countAttempt() { paymentAttempts++; }

    @Override
    public String toString() { return "Order#" + id; }
}
