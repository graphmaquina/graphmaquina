package io.stategraph.fixtures;

import io.stategraph.api.Machine;

/** Facade types for the fixtures: what a @StateGraph interface is at runtime, without the annotations. */
public final class Facades {

    /** Facade of the order machine. */
    public interface Orders extends Machine<Order, OrderStatus> { }

    /** Facade of the item machine. */
    public interface Items extends Machine<Item, ItemStatus> { }

    /** Facade of the doc machine. */
    public interface Docs extends Machine<Doc, DocStatus> { }

    private Facades() { }
}
