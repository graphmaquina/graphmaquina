package pay;

/** The states of a {@link Payment}: pending until it is captured or declined. */
public enum PaymentStatus {
    PENDING,
    CAPTURED,
    DECLINED
}
