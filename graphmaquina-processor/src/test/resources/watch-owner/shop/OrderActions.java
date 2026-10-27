package shop;

import io.github.graphmaquina.annotation.GraphAction;
import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.TransitionContext;

/** An action of the operation that pays an order, on an instance host of its own. */
public final class OrderActions {
    @GraphAction public ActionOutcome charge(TransitionContext<Order, OrderStatus, OrderTransition> context) {
        return ActionOutcome.ok();
    }
}
