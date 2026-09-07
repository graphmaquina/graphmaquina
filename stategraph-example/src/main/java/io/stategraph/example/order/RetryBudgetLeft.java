package io.stategraph.example.order;

import io.stategraph.api.GuardContext;
import io.stategraph.api.GuardResult;
import io.stategraph.api.TransitionGuard;

/** The graph says a retry is possible; this guard says how many times. */
public final class RetryBudgetLeft implements TransitionGuard<Order, OrderStatus> {

    private static final int MAX_ATTEMPTS = 3;

    @Override
    public GuardResult test(GuardContext<Order, OrderStatus> ctx) {
        return ctx.entity().paymentAttempts() < MAX_ATTEMPTS
                ? GuardResult.pass()
                : GuardResult.reject("exhausted the " + MAX_ATTEMPTS + " charge attempts");
    }
}
