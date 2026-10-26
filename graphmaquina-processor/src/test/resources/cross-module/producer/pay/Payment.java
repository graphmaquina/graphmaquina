package pay;

import io.github.graphmaquina.annotation.GraphProperty;
import java.util.Objects;

/** A payment, the entity of {@link Payments}. It names no booking, nor anything else a consumer binds it to. */
public final class Payment {

    private final String id;

    @GraphProperty
    private PaymentStatus status = PaymentStatus.PENDING;

    /** A pending payment identified by {@code id}. */
    public Payment(String id) {
        this.id = Objects.requireNonNull(id);
    }

    @GraphProperty
    public String id() {
        return id;
    }

    public PaymentStatus status() {
        return status;
    }

    public void status(PaymentStatus value) {
        status = Objects.requireNonNull(value);
    }
}
