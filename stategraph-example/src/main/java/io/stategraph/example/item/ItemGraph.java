package io.stategraph.example.item;

import static io.stategraph.example.item.ItemStatus.CANCELLED;
import static io.stategraph.example.item.ItemStatus.PACKED;
import static io.stategraph.example.item.ItemStatus.PACKING;
import static io.stategraph.example.item.ItemStatus.PENDING;

import io.stategraph.api.GraphDefinition;
import io.stategraph.dsl.StateGraph;
import io.stategraph.example.Machines;

/** Four states, sixteen cells. */
public final class ItemGraph {

    private ItemGraph() { }

    public static GraphDefinition<Item, ItemStatus> define(PackItem packItem) {
        return StateGraph.define(Machines.ITEMS)
                .initial(PENDING)
                .terminal(PACKED)
                .terminal(CANCELLED)
                .from(PENDING).to(PACKING).move().to(CANCELLED).move().stay()
                              .forbidOthers("an item is packed by going through PACKING")
                .from(PACKING).to(PACKED).action(packItem).to(CANCELLED).move().stay()
                              .forbidOthers("cannot go back to pending")
                .from(PACKED).stay().forbidOthers("packed is final")
                .from(CANCELLED).stay().forbidOthers("cancelled is final")
                .build();
    }
}
