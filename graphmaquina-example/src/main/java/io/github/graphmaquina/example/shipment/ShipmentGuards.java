package io.github.graphmaquina.example.shipment;

import io.github.graphmaquina.annotation.GraphGuard;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;

/**
 * The guards of the shipment's edges: each passes when the carrier's report, the call's input, names its event, and
 * refuses otherwise with the reason the caller reads. Static methods, so the machine needs no instance of this class.
 */
public final class ShipmentGuards {

    private ShipmentGuards() { }

    @GraphGuard
    public static GuardResult delivered(GuardContext<Shipment, ShipmentStatus, ShipmentTransition> ctx) {
        return reports(ctx, "delivered") ? GuardResult.pass() : GuardResult.reject("the carrier reported no delivery");
    }

    @GraphGuard
    public static GuardResult returned(GuardContext<Shipment, ShipmentStatus, ShipmentTransition> ctx) {
        return reports(ctx, "returned") ? GuardResult.pass() : GuardResult.reject("the carrier reported no return");
    }

    private static boolean reports(GuardContext<Shipment, ShipmentStatus, ShipmentTransition> ctx, String event) {
        return ctx.input(CarrierReport.class).map(CarrierReport::event).filter(event::equals).isPresent();
    }
}
