package book;

import io.github.graphmaquina.annotation.GraphProperty;
import java.util.Optional;
import pay.Payment;

/** Booking. */
public final class Booking {
    private final String id;
    @GraphProperty private BookingStatus status = BookingStatus.REQUESTED;
    private final Payment deposit;
    public Booking(String id, Payment deposit) { this.id = id; this.deposit = deposit; }
    @GraphProperty public String id() { return id; }
    public BookingStatus status() { return status; }
    public void status(BookingStatus value) { status = value; }
    @GraphProperty public Optional<Payment> deposit() { return Optional.of(deposit); }
}
