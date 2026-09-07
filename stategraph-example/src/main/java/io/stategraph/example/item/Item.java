package io.stategraph.example.item;

import io.stategraph.example.order.Order;
import java.util.Optional;
import lombok.Getter;
import lombok.NonNull;

/** The item's state, optionally owned by the order it belongs to. */
public final class Item {

    @Getter
    private final String id;
    @Getter
    private ItemStatus status = ItemStatus.PENDING;
    @Getter
    private final Optional<Order> order;

    public Item(@NonNull String id, @NonNull Optional<Order> order) {
        this.id = id;
        this.order = order;
        order.ifPresent((Order owner) -> owner.attach(this));
    }

    public void setStatus(@NonNull ItemStatus status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return "Item#" + id;
    }
}
