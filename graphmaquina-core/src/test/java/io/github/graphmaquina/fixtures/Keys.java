package io.github.graphmaquina.fixtures;

import io.github.graphmaquina.api.MachineKey;

public final class Keys {

    public static final MachineKey<Doc, DocStatus> DOCS = MachineKey.of(Doc.class, DocStatus.class);
    public static final MachineKey<Order, OrderStatus> ORDERS = MachineKey.of(Order.class, OrderStatus.class);
    public static final MachineKey<Item, ItemStatus> ITEMS = MachineKey.of(Item.class, ItemStatus.class);

    private Keys() { }
}
