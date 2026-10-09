package io.github.graphmaquina.example.billing;

import io.github.graphmaquina.annotation.GraphProperty;
import java.util.Optional;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** A stay paid in two parts, a deposit and then a balance, each a payment of its own. */
@RequiredArgsConstructor
public final class Booking {

    @GraphProperty
    @Getter
    @NonNull
    private final String id;
    @NonNull
    private final Payment deposit;
    @NonNull
    private final Payment balance;
    @GraphProperty
    @Getter
    private BookingStatus status = BookingStatus.REQUESTED;

    public void setStatus(@NonNull BookingStatus status) {
        this.status = status;
    }

    /** The payment that {@code DEPOSIT} holds. */
    @GraphProperty
    public Optional<Payment> deposit() {
        return Optional.of(deposit);
    }

    /** The payment that {@code BALANCE} holds. */
    @GraphProperty
    public Optional<Payment> balance() {
        return Optional.of(balance);
    }

    @Override
    public String toString() {
        return "Booking#" + id;
    }
}
