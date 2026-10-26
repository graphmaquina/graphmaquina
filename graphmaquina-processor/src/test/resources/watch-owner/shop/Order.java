package shop;

import io.github.graphmaquina.annotation.GraphProperty;
import java.util.ArrayList;
import java.util.List;

/** An order and its items. */
public final class Order {
    private final String id;
    private final List<Item> items = new ArrayList<>();
    @GraphProperty private OrderStatus status = OrderStatus.NEW;
    public Order(String id) { this.id = id; }
    @GraphProperty public String id() { return id; }
    public OrderStatus status() { return status; }
    public void status(OrderStatus value) { status = value; }
    @GraphProperty public List<Item> items() { return items; }
    public Item add(String itemId) {
        Item item = new Item(itemId, this);
        items.add(item);
        return item;
    }
}
