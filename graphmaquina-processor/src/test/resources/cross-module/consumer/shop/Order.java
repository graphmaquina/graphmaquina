package shop;

import io.github.graphmaquina.annotation.GraphProperty;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import stock.Item;

/** An order, the entity of {@link Orders}, and its items, entities of the producer. */
public final class Order {

    private final String id;
    private final List<Item> items;

    @GraphProperty
    private OrderStatus status = OrderStatus.NEW;

    /** A new order identified by {@code id}, of {@code items}. */
    public Order(String id, List<Item> items) {
        this.id = Objects.requireNonNull(id);
        this.items = List.copyOf(items);
    }

    @GraphProperty
    public String id() {
        return id;
    }

    public OrderStatus status() {
        return status;
    }

    public void status(OrderStatus value) {
        status = Objects.requireNonNull(value);
    }

    /** The items of the order: the peers its watch follows. */
    @GraphProperty
    public Collection<Item> items() {
        return items;
    }
}
