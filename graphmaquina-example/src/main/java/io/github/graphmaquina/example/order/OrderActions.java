package io.github.graphmaquina.example.order;

import io.github.graphmaquina.annotation.GraphAction;
import io.github.graphmaquina.annotation.GraphGuard;
import io.github.graphmaquina.annotation.GraphHook;
import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.CascadePolicy;
import io.github.graphmaquina.api.CommitContext;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.example.ShopFlow;
import io.github.graphmaquina.example.item.ItemStatus;
import io.github.graphmaquina.example.item.ItemTransition;
import io.github.graphmaquina.example.item.Items;
import java.util.concurrent.CompletionStage;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** The code behind the order operations. A plain class: {@link Orders} says which method serves which edge. */
@RequiredArgsConstructor
public final class OrderActions {

    private static final int MAX_ATTEMPTS = 3;

    @NonNull
    private final PaymentGateway gateway;
    @NonNull
    private final Carrier carrier;

    /**
     * Domain failure is returned (declined) and a foreign failure is declared (IOException via failsOn). It counts the
     * attempt on the order before calling the gateway, which {@code retryBudgetLeft} reads.
     */
    @GraphAction
    public CompletionStage<ActionOutcome> charge(TransitionContext<Order, OrderStatus, OrderTransition> ctx) {
        ctx.entity().countAttempt();
        return gateway.chargeAsync(ctx.entity()).thenApply((Charge charge) ->
                charge.declined() ? ActionOutcome.failed("declined: " + charge.code()) : ActionOutcome.ok());
    }

    /** The graph says a retry edge exists; this guard says how many times it may be taken. */
    @GraphGuard
    public GuardResult retryBudgetLeft(GuardContext<Order, OrderStatus, OrderTransition> ctx) {
        return ctx.entity().paymentAttempts() < MAX_ATTEMPTS
                ? GuardResult.pass()
                : GuardResult.reject("exhausted the " + MAX_ATTEMPTS + " charge attempts");
    }

    @GraphAction
    public ActionOutcome ship(TransitionContext<Order, OrderStatus, OrderTransition> ctx) {
        return ActionOutcome.ok();
    }

    /** Cross-machine guard: the order reads the state of its items read-only. */
    @GraphGuard
    public GuardResult allItemsPacked(GuardContext<Order, OrderStatus, OrderTransition> ctx) {
        return ctx.peer(Items.class).allIn(ctx.entity().items(), ItemStatus.PACKED)
                ? GuardResult.pass()
                : GuardResult.reject("there are unpacked items");
    }

    /** After-commit hook of ship: the carrier hears about it once SHIPPED is written, and whether that closes the order. */
    @GraphHook
    public void notifyCarrier(Order order, CommitContext<OrderStatus, OrderTransition> commit) {
        carrier.shipped(order, commit.isTerminal());
    }

    /**
     * Single gate for cancellation: the cascade to items lives in one place. A cascaded step receives no input, so
     * it first copies the batch its caller passed into the call's flow, or {@code manual} when the caller passed
     * none, for the items' {@code restock}. It names the item's own {@code CANCEL} identifier, not the
     * {@code CANCELLED} state, because a cascade selects an edge. It uses {@code COLLECT}, so every item is tried
     * and one that cannot be cancelled aborts the order's cancellation with all the item failures aggregated.
     */
    @GraphAction
    public ActionOutcome cancel(TransitionContext<Order, OrderStatus, OrderTransition> ctx) {
        ctx.flow().put(ShopFlow.CANCELLATION, ctx.input(String.class).orElse("manual"));
        ctx.cascadeAll(Items.class, ctx.entity().items(), ItemTransition.CANCEL, CascadePolicy.COLLECT);
        return ActionOutcome.ok();
    }
}
