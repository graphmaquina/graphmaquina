package io.github.graphmaquina.example.order;

/** Whoever picks the parcel up. Told about a shipment only after the order's state is committed. */
public interface Carrier {

    /** {@code orderClosed} says whether the shipment ended the order's flow, as the graph declares it. */
    void shipped(Order order, boolean orderClosed);
}
