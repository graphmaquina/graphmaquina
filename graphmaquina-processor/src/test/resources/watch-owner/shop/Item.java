package shop;

import io.github.graphmaquina.annotation.GraphProperty;
import java.util.Optional;

/** An item, which reads its order both as an Optional and as the order itself. */
public final class Item {
    private final String id;
    private final Order order;
    @GraphProperty private ItemStatus status = ItemStatus.PACKING;
    Item(String id, Order order) { this.id = id; this.order = order; }
    @GraphProperty public String id() { return id; }
    public ItemStatus status() { return status; }
    public void status(ItemStatus value) { status = value; }
    @GraphProperty public Optional<Order> order() { return Optional.of(order); }
    @GraphProperty public Order placedIn() { return order; }
}
