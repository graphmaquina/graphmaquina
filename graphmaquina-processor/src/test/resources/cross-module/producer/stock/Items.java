package stock;

import static stock.ItemStatus.OPEN;
import static stock.ItemStatus.PACKED;
import static stock.ItemTransition.PACK;

import io.github.graphmaquina.annotation.GenerateGraphApi;
import io.github.graphmaquina.api.Machine;
import io.github.graphmaquina.api.Outcome;

/**
 * The item machine the producer publishes: an item is packed once. It knows no machine that watches it, so any
 * consumer can watch its items.
 */
@GenerateGraphApi
@ItemsGraph.Definition(
    initial = OPEN,
    terminals = PACKED,
    state = ItemRefs.Status.class,
    id = ItemRefs.Id.class)
@ItemsGraph.StatePolicy(state = OPEN, otherwise = "an item is packed")
public interface Items extends Machine<Item, ItemStatus, ItemTransition> {

    @ItemsGraph.Transition(id = PACK, from = OPEN, to = PACKED)
    Outcome<Item, ItemStatus, ItemTransition> pack(Item item);
}
