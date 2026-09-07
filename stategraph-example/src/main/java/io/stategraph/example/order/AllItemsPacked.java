package io.stategraph.example.order;

import io.stategraph.api.GuardContext;
import io.stategraph.api.GuardResult;
import io.stategraph.api.TransitionGuard;
import io.stategraph.example.Machines;
import io.stategraph.example.item.ItemStatus;

/** Cross-machine guard: Order reads the state of its Items read-only. */
public final class AllItemsPacked implements TransitionGuard<Order, OrderStatus> {

    @Override
    public GuardResult test(GuardContext<Order, OrderStatus> ctx) {
        return ctx.peer(Machines.ITEMS).allIn(ctx.entity().items(), ItemStatus.PACKED)
                ? GuardResult.pass()
                : GuardResult.reject("there are unpacked items");
    }
}
