package io.github.graphmaquina.example.item;

import io.github.graphmaquina.annotation.GraphAction;
import io.github.graphmaquina.annotation.GraphHook;
import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.CommitContext;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.example.ShopFlow;
import io.github.graphmaquina.example.order.Order;

/** The code behind the item operations. */
public final class ItemActions {

    @GraphAction
    public ActionOutcome pack(TransitionContext<Item, ItemStatus, ItemTransition> ctx) {
        return ActionOutcome.ok();
    }

    /**
     * Action of CANCEL, which only the order's cancel reaches: the root of the call is that order, and its batch is
     * in the flow. It leaves the item's restock in the flow and changes no entity, because the step of another item
     * can still abort the call, and nothing would undo the change.
     */
    @GraphAction
    public ActionOutcome restock(TransitionContext<Item, ItemStatus, ItemTransition> ctx) {
        String batch = ctx.flow().get(ShopFlow.CANCELLATION).orElseThrow();
        Order order = ctx.flow().entity(Order.class).orElseThrow();
        ctx.flow().put(ShopFlow.restockOf(ctx.entity()), new Restock(batch, order));
        return ActionOutcome.ok();
    }

    /** After-commit hook of CANCEL: once CANCELLED is written, it applies the restock that the item's step left. */
    @GraphHook
    public void applyRestock(Item item, CommitContext<ItemStatus, ItemTransition> commit) {
        Restock restock = commit.flow().get(ShopFlow.restockOf(item)).orElseThrow();
        item.restockedIn(restock.batch());
        restock.order().restocked(item);
    }
}
