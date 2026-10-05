package io.github.graphmaquina.fixtures;

import io.github.graphmaquina.api.MachineKey;

/** The machine keys of the doc, order and item fixture machines. */
public final class Keys {

    public static final MachineKey<Doc, DocStatus, DocTransition> DOCS =
            MachineKey.of(Doc.class, DocStatus.class, DocTransition.class);
    public static final MachineKey<Order, OrderStatus, OrderTransition> ORDERS =
            MachineKey.of(Order.class, OrderStatus.class, OrderTransition.class);
    public static final MachineKey<Item, ItemStatus, ItemTransition> ITEMS =
            MachineKey.of(Item.class, ItemStatus.class, ItemTransition.class);

    private Keys() { }
}
