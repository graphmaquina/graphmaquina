package io.github.graphmaquina.fixtures;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.runtime.MachineRegistry;
import io.github.graphmaquina.runtime.StateMachines;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** Registries that hold the order and item fixture machines over the graphs of {@link OrderGraphs}. */
public final class OrderMachines {

    private OrderMachines() { }

    /**
     * Orders that watch their items from PAID and ship once every item is packed; {@code ship} ships them and
     * {@code cancel} cancels them.
     */
    public static MachineRegistry registry(Probe<Order, OrderStatus, OrderTransition> ship,
                                           Probe<Order, OrderStatus, OrderTransition> cancel) {
        return MachineRegistry.builder()
                .add(StateMachines.from(OrderGraphs.order(
                                (TransitionContext<Order, OrderStatus, OrderTransition> ctx) ->
                                        CompletableFuture.completedFuture(ActionOutcome.ok()),
                                ship, cancel,
                                (GuardContext<Order, OrderStatus, OrderTransition> ctx) -> GuardResult.pass(),
                                (GuardContext<Order, OrderStatus, OrderTransition> ctx) -> GuardResult.pass()))
                        .state(Order::status, Order::setStatus).identity(Order::id).asyncTimeout(Duration.ofSeconds(5)))
                .add(StateMachines.from(OrderGraphs.item(
                                (TransitionContext<Item, ItemStatus, ItemTransition> ctx) -> ActionOutcome.ok()))
                        .state(Item::status, Item::setStatus).identity(Item::id))
                .build();
    }
}
