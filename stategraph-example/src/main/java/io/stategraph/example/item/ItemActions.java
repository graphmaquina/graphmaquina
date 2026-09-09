package io.stategraph.example.item;

import io.stategraph.annotation.GraphAction;
import io.stategraph.api.ActionOutcome;
import io.stategraph.api.TransitionContext;

/** The code behind the item operations. */
public final class ItemActions {

    @GraphAction
    public ActionOutcome pack(TransitionContext<Item, ItemStatus> ctx) {
        return ActionOutcome.ok();
    }
}
