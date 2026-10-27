package shop;

import io.github.graphmaquina.annotation.GraphCondition;
import io.github.graphmaquina.api.PeerView;
import java.util.Collection;

/** A condition of the watch, on an instance host of its own. */
public final class OrderConditions {
    @GraphCondition public boolean ready(Order order, Collection<? extends Item> items,
            PeerView<Item, ItemStatus, ItemTransition> view) {
        return view.allIn(items, ItemStatus.PACKED);
    }
}
