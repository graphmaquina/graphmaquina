package shop;

import io.github.graphmaquina.annotation.GraphLookup;
import java.util.Optional;

/** The owner lookup of a second watch, the one of an unpaid order, on an instance host of its own. */
public final class UnpaidOrderLookups {
    @GraphLookup public Optional<Order> unpaidOrderOf(Item item) {
        return OrderLookups.orderOf(item);
    }
}
