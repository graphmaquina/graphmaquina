package io.github.graphmaquina.example.shipment;

import io.github.graphmaquina.annotation.StateGraphs;
import io.github.graphmaquina.api.Machines;

/** The shipment's machine, built by the generated ShipmentMachinesRuntime. */
@StateGraphs
public interface ShipmentMachines extends Machines {

    Shipments shipments();
}
