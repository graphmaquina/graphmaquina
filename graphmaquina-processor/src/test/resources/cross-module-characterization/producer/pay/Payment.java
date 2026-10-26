package pay;

import io.github.graphmaquina.annotation.GraphProperty;

/** Payment. */
public final class Payment {
    private final String id;
    @GraphProperty private PaymentStatus status = PaymentStatus.PENDING;
    public Payment(String id) { this.id = id; }
    @GraphProperty public String id() { return id; }
    public PaymentStatus status() { return status; }
    public void status(PaymentStatus value) { status = value; }
}
