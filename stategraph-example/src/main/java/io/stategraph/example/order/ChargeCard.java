package io.stategraph.example.order;

import io.stategraph.api.ActionOutcome;
import io.stategraph.api.AsyncTransitionAction;
import io.stategraph.api.TransitionContext;
import java.util.concurrent.CompletionStage;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** Domain failure is returned (declined) and a foreign failure is declared (IOException via failsOn). */
@RequiredArgsConstructor
public final class ChargeCard implements AsyncTransitionAction<Order, OrderStatus> {

    @NonNull
    private final PaymentGateway gateway;

    @Override
    public CompletionStage<ActionOutcome> process(TransitionContext<Order, OrderStatus> ctx) {
        ctx.entity().countAttempt();
        return gateway.chargeAsync(ctx.entity()).thenApply((Charge charge) ->
                charge.declined() ? ActionOutcome.failed("declined: " + charge.code()) : ActionOutcome.ok());
    }
}
