package io.github.graphmaquina.example.shipment;

import static io.github.graphmaquina.example.shipment.ShipmentStatus.DELIVERED;
import static io.github.graphmaquina.example.shipment.ShipmentStatus.IN_TRANSIT;
import static io.github.graphmaquina.example.shipment.ShipmentTransition.DELIVER;
import static io.github.graphmaquina.example.shipment.ShipmentTransition.RETURN;
import static io.github.graphmaquina.test.GraphAssertions.assertThat;

import io.github.graphmaquina.api.IgnoreReason;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.test.RejectionsAssert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The shipment's choice, asserted through the public testing API. A carrier's report runs the first of {@code RETURN}
 * and {@code DELIVER} whose guard admits it; a report that neither admits leaves the shipment in transit; the check
 * names the candidate the choice would run, without running it; and a delivered shipment refuses both candidates with
 * the reason of its policy.
 */
class ShipmentFlowTest {

    final ShipmentMachines machines = ShipmentMachinesRuntime.builder().build();
    final Shipments shipments = machines.shipments();

    @AfterEach
    void close() {
        machines.close();
    }

    @Test
    void aDeliveryReportDelivers() {
        Shipment shipment = new Shipment("s1");

        Outcome<Shipment, ShipmentStatus, ShipmentTransition> outcome =
                shipments.observe(shipment, new CarrierReport("delivered"));

        assertThat(outcome).isApplied().requestedTransition(DELIVER).movedTo(DELIVERED)
                .hasEntityState(Shipment::status, DELIVERED);
    }

    @Test
    void aReportNoGuardAdmitsLeavesTheShipmentInTransit() {
        Shipment shipment = new Shipment("s2");

        Outcome<Shipment, ShipmentStatus, ShipmentTransition> outcome =
                shipments.observe(shipment, new CarrierReport("delayed"));

        RejectionsAssert<Shipment, ShipmentStatus, ShipmentTransition> rejections = assertThat(outcome).isIgnored()
                .hasReason(IgnoreReason.NO_CANDIDATE_ADMITTED).hasEntityState(Shipment::status, IN_TRANSIT)
                .hasExplanation("no candidate was admitted: RETURN (GUARD returned): the carrier reported no return;"
                        + " DELIVER (GUARD delivered): the carrier reported no delivery")
                .rejections().hasSize(2);
        rejections.atIndex(0).isRejectedByGuardNamed(ShipmentGuardsRefs.Returned.NAME).transition(RETURN);
        rejections.atIndex(1).isRejectedByGuardNamed(ShipmentGuardsRefs.Delivered.NAME).transition(DELIVER);
    }

    @Test
    void theCheckNamesTheCandidateItWouldTake() {
        Shipment shipment = new Shipment("s3");

        assertThat(shipments.canObserve(shipment, new CarrierReport("delivered"))).chose(DELIVER).from(IN_TRANSIT)
                .rejections().single().isRejectedByGuardNamed(ShipmentGuardsRefs.Returned.NAME);
        assertThat(shipment, Shipment::status).isIn(IN_TRANSIT);
    }

    @Test
    void aDeliveredShipmentRefusesEveryCandidateWithItsPolicy() {
        Shipment shipment = new Shipment("s4");
        shipments.observe(shipment, new CarrierReport("delivered"));

        RejectionsAssert<Shipment, ShipmentStatus, ShipmentTransition> rejections =
                assertThat(shipments.observe(shipment, new CarrierReport("returned"))).isIgnored()
                        .hasReason(IgnoreReason.NO_CANDIDATE_ADMITTED).rejections().hasSize(2);
        rejections.atIndex(0).hasNoTransition().hasReason(Shipments.DELIVERED_POLICY).transition(RETURN);
        rejections.atIndex(1).hasNoTransition().hasReason(Shipments.DELIVERED_POLICY).transition(DELIVER);
        assertThat(shipments.canObserve(shipment, new CarrierReport("returned"))).admittedNone().from(DELIVERED);
    }
}
