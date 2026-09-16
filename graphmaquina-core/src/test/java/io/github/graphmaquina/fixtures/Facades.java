package io.github.graphmaquina.fixtures;

import io.github.graphmaquina.api.Machine;

/** Facade types for the fixtures: what a generated machine interface is at runtime, without the annotations. */
public final class Facades {

    /** Facade of the order machine. */
    public interface Orders extends Machine<Order, OrderStatus, OrderTransition> { }

    /** Facade of the item machine. */
    public interface Items extends Machine<Item, ItemStatus, ItemTransition> { }

    /** Facade of the doc machine. */
    public interface Docs extends Machine<Doc, DocStatus, DocTransition> { }

    private Facades() { }
}
