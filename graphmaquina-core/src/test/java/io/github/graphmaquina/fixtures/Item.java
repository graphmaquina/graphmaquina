package io.github.graphmaquina.fixtures;

import java.util.Optional;

public final class Item {

    private final String id;
    private ItemStatus status;
    private final Optional<Order> order;

    public Item(String id, ItemStatus status, Optional<Order> order) {
        this.id = id;
        this.status = status;
        this.order = order;
        order.ifPresent((Order owner) -> owner.add(this));
    }

    public String id() { return id; }

    public ItemStatus status() { return status; }

    public void setStatus(ItemStatus status) { this.status = status; }

    public Optional<Order> order() { return order; }

    @Override
    public String toString() { return "Item#" + id; }
}
