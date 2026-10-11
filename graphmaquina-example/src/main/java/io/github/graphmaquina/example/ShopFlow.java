package io.github.graphmaquina.example;

import io.github.graphmaquina.api.FlowKey;
import io.github.graphmaquina.example.item.Item;
import io.github.graphmaquina.example.item.Restock;

/**
 * The keys under which the shop's steps and hooks share values in the call's flow. The order's cancel puts the batch
 * its caller passed, the restock action of each item it cancels reads it and puts that item's restock, and the item's
 * hook applies that restock once the call committed.
 */
public final class ShopFlow {

    /** The batch a cancellation restocks its items in. Visible: a TRACE line of its write prints it. */
    public static final FlowKey<String> CANCELLATION = FlowKey.visible("cancellation", String.class);

    private ShopFlow() { }

    /** The key of the restock of {@code item}: one key per item, since all steps and hooks of a call share the flow. */
    public static FlowKey<Restock> restockOf(Item item) {
        return FlowKey.of("restock:" + item.id(), Restock.class);
    }
}
