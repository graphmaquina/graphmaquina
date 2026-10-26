package shop;

import io.github.graphmaquina.annotation.GenerateGraphApi;
import io.github.graphmaquina.api.Machine;
import io.github.graphmaquina.api.Outcome;
import static shop.ItemStatus.*;
import static shop.ItemTransition.*;

/** Items, which the orders watch. */
@GenerateGraphApi
@ItemsGraph.Definition(initial = PACKING, terminals = PACKED,
    state = ItemRefs.Status.class, id = ItemRefs.Id.class)
@ItemsGraph.StatePolicy(state = PACKING, otherwise = "an item is packed")
public interface Items extends Machine<Item, ItemStatus, ItemTransition> {
    @ItemsGraph.Transition(id = PACK, from = PACKING, to = PACKED)
    Outcome<Item, ItemStatus, ItemTransition> pack(Item item);
}
