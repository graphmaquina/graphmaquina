package io.github.graphmaquina.example.billing;

import io.github.graphmaquina.annotation.GraphProperty;
import java.util.Optional;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** A subscription that charges the same renewal payment again in every period. */
@RequiredArgsConstructor
public final class Subscription {

    @GraphProperty
    @Getter
    @NonNull
    private final String id;
    @NonNull
    private final Payment renewal;
    @GraphProperty
    @Getter
    private SubscriptionStatus status = SubscriptionStatus.ACTIVE;

    public void setStatus(@NonNull SubscriptionStatus status) {
        this.status = status;
    }

    /** The payment that {@code RENEWAL} holds. */
    @GraphProperty
    public Optional<Payment> renewal() {
        return Optional.of(renewal);
    }

    @Override
    public String toString() {
        return "Subscription#" + id;
    }
}
