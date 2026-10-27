package shop;

import io.github.graphmaquina.annotation.GraphLookup;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import stock.Item;

/**
 * Finds the order of an item, the way back from a peer to its owner. An item of the producer cannot name its order,
 * so the consumer supplies this lookup. It keeps the orders it was told about by the id of each of their items, where
 * an application would ask its repository.
 */
public final class OrderLookups {

    private final Map<String, Order> orders = new HashMap<>();

    /** Remembers {@code order} as the order of each of its items. */
    public void register(Order order) {
        for (Item item : order.items()) {
            orders.put(item.id(), order);
        }
    }

    @GraphLookup
    public Optional<Order> orderOf(Item item) {
        return Optional.ofNullable(orders.get(item.id()));
    }
}
