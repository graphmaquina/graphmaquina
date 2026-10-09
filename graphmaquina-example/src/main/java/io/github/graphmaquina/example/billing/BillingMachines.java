package io.github.graphmaquina.example.billing;

import io.github.graphmaquina.annotation.StateGraphs;
import io.github.graphmaquina.api.Machines;

/**
 * The three billing machines, built together by the generated BillingMachinesRuntime: each child's definition once,
 * before the parents that bind it.
 */
@StateGraphs
public interface BillingMachines extends Machines {

    Bookings bookings();

    Subscriptions subscriptions();

    Payments payments();
}
