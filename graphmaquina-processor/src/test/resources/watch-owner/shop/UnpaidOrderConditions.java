package shop;

import io.github.graphmaquina.annotation.GraphCondition;
import io.github.graphmaquina.api.PeerView;
import java.util.Collection;

/** A condition of a second watch, the one of an unpaid order, on an instance host of its own. */
public final class UnpaidOrderConditions {
    @GraphCondition public boolean packed(Order order, Collection<? extends Item> items,
            PeerView<Item, ItemStatus, ItemTransition> view) {
        return view.allIn(items, ItemStatus.PACKED);
    }
}
