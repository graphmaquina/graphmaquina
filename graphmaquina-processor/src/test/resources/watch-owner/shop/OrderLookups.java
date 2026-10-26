package shop;

import io.github.graphmaquina.annotation.GraphCondition;
import io.github.graphmaquina.annotation.GraphLookup;
import io.github.graphmaquina.api.PeerView;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Finds the order of an item among the orders it was told about, as an application would ask its repository, in each
 * way a watch of the tests names: static, on an instance, never, as null, or declaring a checked exception. It also
 * exports a lookup that takes the wrong entity, and a condition of the watch.
 */
public final class OrderLookups {
    private static final Map<String, Order> ORDERS = new HashMap<>();

    /** Remembers the order of each of its items. */
    public static void register(Order order) {
        for (Item item : order.items()) {
            ORDERS.put(item.id(), order);
        }
    }

    @GraphLookup public static Optional<Order> orderOf(Item item) {
        return Optional.ofNullable(ORDERS.get(item.id()));
    }

    @GraphLookup public Optional<Order> heldOrderOf(Item item) {
        return orderOf(item);
    }

    @GraphLookup public static Optional<Order> noOrder(Item item) {
        return Optional.empty();
    }

    @GraphLookup public static Optional<Order> nullOrder(Item item) {
        return null;
    }

    @GraphLookup public static Optional<Order> storedOrderOf(Item item) throws IOException {
        return orderOf(item);
    }

    @GraphLookup public static Optional<Order> unrelated(Order order) {
        return Optional.of(order);
    }

    @GraphCondition public boolean packed(Order order, Collection<? extends Item> items,
            PeerView<Item, ItemStatus, ItemTransition> view) {
        return view.allIn(items, ItemStatus.PACKED);
    }
}
