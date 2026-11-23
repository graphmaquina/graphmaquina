package io.github.graphmaquina.example.shipment;

import static io.github.graphmaquina.example.shipment.ShipmentStatus.DELIVERED;
import static io.github.graphmaquina.example.shipment.ShipmentStatus.IN_TRANSIT;
import static io.github.graphmaquina.example.shipment.ShipmentStatus.RETURNED;
import static io.github.graphmaquina.example.shipment.ShipmentTransition.DELIVER;
import static io.github.graphmaquina.example.shipment.ShipmentTransition.RETURN;

import io.github.graphmaquina.annotation.GenerateGraphApi;
import io.github.graphmaquina.api.ChoiceEligibility;
import io.github.graphmaquina.api.Machine;
import io.github.graphmaquina.api.Outcome;

/**
 * The shipment machine: a shipment in transit is delivered or returned, as its carrier reports. The application hands
 * the report to the choice {@code observe}, which runs the first of RETURN and DELIVER whose guard admits it, so it
 * never decides itself which edge the report means. The processor generates ShipmentsRuntime.
 */
@GenerateGraphApi
@ShipmentsGraph.Definition(initial = IN_TRANSIT, terminals = {DELIVERED, RETURNED},
        state = ShipmentRefs.Status.class, id = ShipmentRefs.Id.class)
@ShipmentsGraph.StatePolicy(state = IN_TRANSIT, otherwise = Shipments.IN_TRANSIT_POLICY)
@ShipmentsGraph.StatePolicy(state = DELIVERED, otherwise = Shipments.DELIVERED_POLICY)
@ShipmentsGraph.StatePolicy(state = RETURNED, otherwise = Shipments.RETURNED_POLICY)
@ShipmentsGraph.Transition(id = DELIVER, from = IN_TRANSIT, to = DELIVERED, guards = ShipmentGuardsRefs.Delivered.class)
@ShipmentsGraph.Transition(id = RETURN, from = IN_TRANSIT, to = RETURNED, guards = ShipmentGuardsRefs.Returned.class)
public interface Shipments extends Machine<Shipment, ShipmentStatus, ShipmentTransition> {

    /** Runs the first of RETURN and DELIVER that the report admits, or returns Ignored when it admits neither. */
    @ShipmentsGraph.Choice({RETURN, DELIVER})
    Outcome<Shipment, ShipmentStatus, ShipmentTransition> observe(Shipment shipment, CarrierReport report);

    /** Which of RETURN and DELIVER {@code observe} would run for the report now, without running it. */
    @ShipmentsGraph.Choice({RETURN, DELIVER})
    ChoiceEligibility<Shipment, ShipmentStatus, ShipmentTransition> canObserve(Shipment shipment, CarrierReport report);

    String IN_TRANSIT_POLICY = "a shipment in transit is delivered or returned";
    String DELIVERED_POLICY = "the shipment was delivered";
    String RETURNED_POLICY = "the shipment went back to its sender";
}
