package io.stategraph.example.item;

import io.stategraph.annotation.Action;
import io.stategraph.annotation.Cell;
import io.stategraph.annotation.Move;
import io.stategraph.annotation.Row;
import io.stategraph.annotation.StateGraph;
import io.stategraph.api.Machine;
import io.stategraph.api.Outcome;

/** The item machine: four states, sixteen cells. Cancellation is reached only by the order's cascade. */
@StateGraph(initial = "PENDING", terminals = {"PACKED", "CANCELLED"})
@Row(state = "PENDING", otherwise = "an item is packed by going through PACKING")
@Row(state = "PACKING", otherwise = "cannot go back to pending")
@Cell(from = {"PENDING", "PACKING"}, to = "CANCELLED")
public interface Items extends Machine<Item, ItemStatus> {

    @Move(from = "PENDING", to = "PACKING")
    Outcome<Item, ItemStatus> startPacking(Item item);

    @Action(from = "PACKING", to = "PACKED", by = ItemActions.class)
    Outcome<Item, ItemStatus> pack(Item item);
}
