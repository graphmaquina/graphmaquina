package io.stategraph.example.item;

import static io.stategraph.example.item.ItemStatus.CANCELLED;
import static io.stategraph.example.item.ItemStatus.PACKED;
import static io.stategraph.example.item.ItemStatus.PACKING;
import static io.stategraph.example.item.ItemStatus.PENDING;

import io.stategraph.annotation.GenerateGraphApi;
import io.stategraph.api.Machine;
import io.stategraph.api.Outcome;

/** The item machine: four states, sixteen cells. Cancellation is reached only by the order's cascade. */
@GenerateGraphApi
@ItemsGraph.Definition(initial = PENDING, terminals = {PACKED, CANCELLED},
        state = ItemRefs.Status.class, id = ItemRefs.Id.class)
@ItemsGraph.Row(state = PENDING, otherwise = "an item is packed by going through PACKING")
@ItemsGraph.Row(state = PACKING, otherwise = "cannot go back to pending")
@ItemsGraph.Cell(from = {PENDING, PACKING}, to = CANCELLED)
public interface Items extends Machine<Item, ItemStatus> {

    @ItemsGraph.Move(from = PENDING, to = PACKING)
    Outcome<Item, ItemStatus> startPacking(Item item);

    @ItemsGraph.Action(from = PACKING, to = PACKED, action = ItemActionsRefs.Pack.class)
    Outcome<Item, ItemStatus> pack(Item item);
}
