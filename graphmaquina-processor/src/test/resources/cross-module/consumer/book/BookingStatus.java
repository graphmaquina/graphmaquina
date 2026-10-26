package book;

/** The states of a {@link Booking}: {@code DEPOSIT} holds the payment of its deposit. */
public enum BookingStatus {
    REQUESTED,
    DEPOSIT,
    CONFIRMED,
    CANCELLED
}
