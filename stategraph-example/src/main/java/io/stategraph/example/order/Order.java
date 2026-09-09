package io.stategraph.example.order;

import io.stategraph.annotation.GraphProperty;
import io.stategraph.example.item.Item;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** The state lives here and starts already initialized: the machine never auto-initializes it. */
@RequiredArgsConstructor
public final class Order {

    @GraphProperty
    @Getter
    @NonNull
    private final String id;
    @GraphProperty
    @Getter
    private OrderStatus status = OrderStatus.NEW;
    private final List<Item> items = new ArrayList<>();
    @Getter
    private int paymentAttempts;

    public void setStatus(@NonNull OrderStatus status) {
        this.status = status;
    }

    @GraphProperty
    public List<Item> items() {
        return List.copyOf(items);
    }

    /** Only called by the Item constructor. */
    public void attach(@NonNull Item item) {
        items.add(item);
    }

    public void countAttempt() {
        paymentAttempts++;
    }

    @Override
    public String toString() {
        return "Order#" + id;
    }
}
