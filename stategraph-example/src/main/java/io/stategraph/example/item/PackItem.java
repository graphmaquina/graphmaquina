package io.stategraph.example.item;

import io.stategraph.api.ActionOutcome;
import io.stategraph.api.TransitionAction;
import io.stategraph.api.TransitionContext;

/** Packs an item; a plain move with a name for the diagram. */
public final class PackItem implements TransitionAction<Item, ItemStatus> {

    @Override
    public ActionOutcome process(TransitionContext<Item, ItemStatus> ctx) {
        return ActionOutcome.ok();
    }
}
