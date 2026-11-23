package io.github.graphmaquina.example.shipment;

/** States of a shipment: on its way, then delivered or returned to its sender, both terminal. */
public enum ShipmentStatus { IN_TRANSIT, DELIVERED, RETURNED }
