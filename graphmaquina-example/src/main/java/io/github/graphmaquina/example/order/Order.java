package io.github.graphmaquina.example.order;

import io.github.graphmaquina.annotation.GraphProperty;
import io.github.graphmaquina.example.item.Item;
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
    private final List<Item> restocked = new ArrayList<>();
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

    /** The items its cancellation put back in stock, in the order their hooks ran. */
    public List<Item> restocked() {
        return List.copyOf(restocked);
    }

    /** Only called by the hook of an item's cancellation, once the call committed. */
    public void restocked(@NonNull Item item) {
        restocked.add(item);
    }

    @Override
    public String toString() {
        return "Order#" + id;
    }
}
