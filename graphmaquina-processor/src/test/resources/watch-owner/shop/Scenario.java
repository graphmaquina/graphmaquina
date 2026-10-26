package shop;

import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.Reaction;

/**
 * Pays an order of two items, then packs them one by one, and reports the order's state after the first and after the
 * second, followed by the cause of each reaction the second packing ended aborted. The builder takes the instance hosts
 * the watch calls, none as written here.
 */
public final class Scenario {
    private Scenario() { }

    public static String run() {
        try (Shop shop = ShopRuntime.builder(/* hosts */).build()) {
            Order order = new Order("order");
            Item first = order.add("first");
            Item second = order.add("second");
            OrderLookups.register(order);
            shop.orders().pay(order);
            shop.items().pack(first);
            String between = order.status().name();
            Outcome<Item, ItemStatus, ItemTransition> last = shop.items().pack(second);
            return between + " " + order.status() + aborted(last);
        }
    }

    /** {@code , Cause: message} for each reaction of {@code outcome} that ended aborted, in order. */
    private static String aborted(Outcome<Item, ItemStatus, ItemTransition> outcome) {
        StringBuilder text = new StringBuilder();
        for (Reaction reaction : outcome.reactions()) {
            if (reaction instanceof Reaction.Aborted aborted) {
                text.append(", ").append(aborted.cause().getClass().getSimpleName()).append(": ")
                        .append(aborted.cause().getMessage());
            }
        }
        return text.toString();
    }
}
