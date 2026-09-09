package io.github.graphmaquina.example.item;

import static io.github.graphmaquina.example.item.ItemStatus.CANCELLED;
import static io.github.graphmaquina.example.item.ItemStatus.PACKED;
import static io.github.graphmaquina.example.item.ItemStatus.PACKING;
import static io.github.graphmaquina.example.item.ItemStatus.PENDING;

import io.github.graphmaquina.annotation.GenerateGraphApi;
import io.github.graphmaquina.api.Machine;
import io.github.graphmaquina.api.Outcome;

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
