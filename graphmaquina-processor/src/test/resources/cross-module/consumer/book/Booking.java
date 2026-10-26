package book;

import io.github.graphmaquina.annotation.GraphProperty;
import java.util.Objects;
import java.util.Optional;
import pay.Payment;

/** A booking, the entity of {@link Bookings}, and the payment of its deposit, an entity of the producer. */
public final class Booking {

    private final String id;
    private final Payment deposit;

    @GraphProperty
    private BookingStatus status = BookingStatus.REQUESTED;

    /** A requested booking identified by {@code id}, whose deposit is paid by {@code deposit}. */
    public Booking(String id, Payment deposit) {
        this.id = Objects.requireNonNull(id);
        this.deposit = Objects.requireNonNull(deposit);
    }

    @GraphProperty
    public String id() {
        return id;
    }

    public BookingStatus status() {
        return status;
    }

    public void status(BookingStatus value) {
        status = Objects.requireNonNull(value);
    }

    /** The payment of the deposit: the sub-machine that {@code DEPOSIT} holds. */
    @GraphProperty
    public Optional<Payment> deposit() {
        return Optional.of(deposit);
    }
}
