package io.github.graphmaquina.example.shipment;

import lombok.NonNull;

/**
 * What a carrier reports about a shipment, such as {@code "delivered"} or {@code "returned"}: the input of the
 * shipment's choice, which the guards of its candidates read.
 */
public record CarrierReport(@NonNull String event) { }
