package io.github.graphmaquina.example.billing;

import io.github.graphmaquina.annotation.GraphProperty;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** A payment. It holds no reference to what it pays for: {@link BillingLookups} finds that. */
@RequiredArgsConstructor
public final class Payment {

    @GraphProperty
    @Getter
    @NonNull
    private final String id;
    @GraphProperty
    @Getter
    private PaymentStatus status = PaymentStatus.AUTHORIZING;

    public void setStatus(@NonNull PaymentStatus status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return "Payment#" + id;
    }
}
