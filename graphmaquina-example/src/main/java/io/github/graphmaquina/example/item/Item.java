package io.github.graphmaquina.example.item;

import io.github.graphmaquina.annotation.GraphProperty;
import io.github.graphmaquina.example.order.Order;
import java.util.Optional;
import lombok.Getter;
import lombok.NonNull;

/** The item's state, optionally owned by the order it belongs to, and the batch it went back to stock in. */
public final class Item {

    @GraphProperty
    @Getter
    private final String id;
    @GraphProperty
    @Getter
    private ItemStatus status = ItemStatus.PENDING;
    @GraphProperty
    @Getter
    private final Optional<Order> order;
    @Getter
    private Optional<String> restockedIn = Optional.empty();

    public Item(@NonNull String id, @NonNull Optional<Order> order) {
        this.id = id;
        this.order = order;
        order.ifPresent((Order owner) -> owner.attach(this));
    }

    public void setStatus(@NonNull ItemStatus status) {
        this.status = status;
    }

    /** Only called by the hook of the item's cancellation, once the call committed. */
    public void restockedIn(@NonNull String batch) {
        restockedIn = Optional.of(batch);
    }

    @Override
    public String toString() {
        return "Item#" + id;
    }
}
