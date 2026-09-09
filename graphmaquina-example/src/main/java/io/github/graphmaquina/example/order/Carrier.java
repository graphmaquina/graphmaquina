package io.github.graphmaquina.example.order;

/** Whoever picks the parcel up. Told about a shipment only after the order's state is committed. */
public interface Carrier {

    void shipped(Order order);
}
