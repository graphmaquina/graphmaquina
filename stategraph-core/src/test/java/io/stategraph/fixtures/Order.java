package io.stategraph.fixtures;

import java.util.ArrayList;
import java.util.List;

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
