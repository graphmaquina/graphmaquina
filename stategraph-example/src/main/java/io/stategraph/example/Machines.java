package io.stategraph.example;

import io.stategraph.api.MachineKey;
import io.stategraph.example.item.Item;
import io.stategraph.example.item.ItemGraph;
import io.stategraph.example.item.ItemStatus;
import io.stategraph.example.item.PackItem;
import io.stategraph.example.order.AllItemsPacked;
import io.stategraph.example.order.CancelOrder;
import io.stategraph.example.order.ChargeCard;
import io.stategraph.example.order.Order;
import io.stategraph.example.order.OrderGraph;
import io.stategraph.example.order.OrderStatus;
import io.stategraph.example.order.PaymentGateway;
import io.stategraph.example.order.RetryBudgetLeft;
import io.stategraph.example.order.ShipOrder;
import io.stategraph.runtime.MachineRegistry;
import io.stategraph.runtime.StateMachines;
import java.time.Duration;
import java.util.Objects;

/** Keys tie entity and enum together: without them, cascade(item, OrderStatus.PAID) would compile. */
public final class Machines {

    public static final MachineKey<Order, OrderStatus> ORDERS = MachineKey.of(Order.class, OrderStatus.class);
    public static final MachineKey<Item, ItemStatus> ITEMS = MachineKey.of(Item.class, ItemStatus.class);

    private Machines() { }

    /** With Spring, this would be a @Bean receiving the actions as injected dependencies. */
    public static MachineRegistry registry(PaymentGateway gateway) {
        Objects.requireNonNull(gateway, "gateway");
        return MachineRegistry.builder()
                .add(StateMachines.from(OrderGraph.define(new ChargeCard(gateway), new ShipOrder(), new CancelOrder(),
                                new RetryBudgetLeft(), new AllItemsPacked()))
                        .state(Order::status, Order::setStatus)
                        .identity(Order::id)
                        .asyncTimeout(Duration.ofSeconds(10)))
                .add(StateMachines.from(ItemGraph.define(new PackItem()))
                        .state(Item::status, Item::setStatus)
                        .identity(Item::id))
                .build();
    }
}
