package io.stategraph.example.order;

import io.stategraph.annotation.GraphAction;
import io.stategraph.annotation.GraphGuard;
import io.stategraph.annotation.GraphHook;
import io.stategraph.api.ActionOutcome;
import io.stategraph.api.CascadePolicy;
import io.stategraph.api.GuardContext;
import io.stategraph.api.GuardResult;
import io.stategraph.api.TransitionContext;
import io.stategraph.example.item.ItemStatus;
import io.stategraph.example.item.Items;
import java.util.concurrent.CompletionStage;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** The code behind the order operations. A plain class: {@link Orders} says which method serves which cell. */
@RequiredArgsConstructor
public final class OrderActions {

    private static final int MAX_ATTEMPTS = 3;

    @NonNull
    private final PaymentGateway gateway;
    @NonNull
    private final Carrier carrier;

    /** Domain failure is returned (declined) and a foreign failure is declared (IOException via failsOn). */
    @GraphAction
    public CompletionStage<ActionOutcome> charge(TransitionContext<Order, OrderStatus> ctx) {
        ctx.entity().countAttempt();
        return gateway.chargeAsync(ctx.entity()).thenApply((Charge charge) ->
                charge.declined() ? ActionOutcome.failed("declined: " + charge.code()) : ActionOutcome.ok());
    }

    /** The graph says a retry is possible; this guard says how many times. */
    @GraphGuard
    public GuardResult retryBudgetLeft(GuardContext<Order, OrderStatus> ctx) {
        return ctx.entity().paymentAttempts() < MAX_ATTEMPTS
                ? GuardResult.pass()
                : GuardResult.reject("exhausted the " + MAX_ATTEMPTS + " charge attempts");
    }

    @GraphAction
    public ActionOutcome ship(TransitionContext<Order, OrderStatus> ctx) {
        return ActionOutcome.ok();
    }

    /** Cross-machine guard: the order reads the state of its items read-only. */
    @GraphGuard
    public GuardResult allItemsPacked(GuardContext<Order, OrderStatus> ctx) {
        return ctx.peer(Items.class).allIn(ctx.entity().items(), ItemStatus.PACKED)
                ? GuardResult.pass()
                : GuardResult.reject("there are unpacked items");
    }

    /** After-commit hook of ship: the carrier hears about it once SHIPPED is written. */
    @GraphHook
    public void notifyCarrier(Order order) {
        carrier.shipped(order);
    }

    /** Single gate for cancellation: the cascade to items lives in one place. */
    @GraphAction
    public ActionOutcome cancel(TransitionContext<Order, OrderStatus> ctx) {
        ctx.cascadeAll(Items.class, ctx.entity().items(), ItemStatus.CANCELLED, CascadePolicy.COLLECT);
        return ActionOutcome.ok();
    }
}
