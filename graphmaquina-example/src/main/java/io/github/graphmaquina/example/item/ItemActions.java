package io.github.graphmaquina.example.item;

import io.github.graphmaquina.annotation.GraphAction;
import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.TransitionContext;

/** The code behind the item operations. */
public final class ItemActions {

    @GraphAction
    public ActionOutcome pack(TransitionContext<Item, ItemStatus, ItemTransition> ctx) {
        return ActionOutcome.ok();
    }
}
