package io.stategraph.example.order;

import io.stategraph.api.ActionOutcome;
import io.stategraph.api.CascadePolicy;
import io.stategraph.api.TransitionAction;
import io.stategraph.api.TransitionContext;
import io.stategraph.example.Machines;
import io.stategraph.example.item.ItemStatus;

/** Single gate for cancellation: the cascade to items lives in one place. */
public final class CancelOrder implements TransitionAction<Order, OrderStatus> {

    @Override
    public ActionOutcome process(TransitionContext<Order, OrderStatus> ctx) {
        ctx.cascadeAll(Machines.ITEMS, ctx.entity().items(), ItemStatus.CANCELLED, CascadePolicy.COLLECT);
        return ActionOutcome.ok();
    }
}
