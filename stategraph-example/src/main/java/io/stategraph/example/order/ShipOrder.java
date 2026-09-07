package io.stategraph.example.order;

import io.stategraph.api.ActionOutcome;
import io.stategraph.api.TransitionAction;
import io.stategraph.api.TransitionContext;

/** Ships the order; a plain move with a name for the diagram. */
public final class ShipOrder implements TransitionAction<Order, OrderStatus> {

    @Override
    public ActionOutcome process(TransitionContext<Order, OrderStatus> ctx) {
        return ActionOutcome.ok();
    }
}
