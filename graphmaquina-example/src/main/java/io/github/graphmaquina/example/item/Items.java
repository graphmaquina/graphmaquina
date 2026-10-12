package io.github.graphmaquina.example.item;

import static io.github.graphmaquina.example.item.ItemStatus.CANCELLED;
import static io.github.graphmaquina.example.item.ItemStatus.PACKED;
import static io.github.graphmaquina.example.item.ItemStatus.PACKING;
import static io.github.graphmaquina.example.item.ItemStatus.PENDING;

import io.github.graphmaquina.annotation.GenerateGraphApi;
import io.github.graphmaquina.api.Machine;
import io.github.graphmaquina.api.Outcome;

/**
 * The item machine: four states and four edges. Cancellation is reached only by the order's cascade; its action and
 * its hook put the item back in stock.
 */
@GenerateGraphApi
@ItemsGraph.Definition(initial = PENDING, terminals = {PACKED, CANCELLED},
        state = ItemRefs.Status.class, id = ItemRefs.Id.class)
@ItemsGraph.StatePolicy(state = PENDING, otherwise = "an item is packed by going through PACKING")
@ItemsGraph.StatePolicy(state = PACKING, otherwise = "cannot go back to pending")
@ItemsGraph.Transition(id = ItemTransition.CANCEL, from = {PENDING, PACKING}, to = CANCELLED,
        action = ItemActionsRefs.Restock.class, afterCommit = ItemActionsRefs.ApplyRestock.class)
public interface Items extends Machine<Item, ItemStatus, ItemTransition> {

    @ItemsGraph.Transition(id = ItemTransition.START_PACKING, from = PENDING, to = PACKING)
    Outcome<Item, ItemStatus, ItemTransition> startPacking(Item item);

    @ItemsGraph.Transition(id = ItemTransition.PACK, from = PACKING, to = PACKED,
            action = ItemActionsRefs.Pack.class)
    Outcome<Item, ItemStatus, ItemTransition> pack(Item item);
}
