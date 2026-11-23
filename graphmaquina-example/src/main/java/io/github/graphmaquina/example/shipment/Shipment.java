package io.github.graphmaquina.example.shipment;

import io.github.graphmaquina.annotation.GraphProperty;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** A shipment on its way. The machine reads and writes its state, which starts at IN_TRANSIT. */
@RequiredArgsConstructor
public final class Shipment {

    @GraphProperty
    @Getter
    @NonNull
    private final String id;
    @GraphProperty
    @Getter
    private ShipmentStatus status = ShipmentStatus.IN_TRANSIT;

    public void setStatus(@NonNull ShipmentStatus status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return "Shipment#" + id;
    }
}
