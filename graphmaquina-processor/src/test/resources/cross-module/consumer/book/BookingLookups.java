package book;

import io.github.graphmaquina.annotation.GraphLookup;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import pay.Payment;

/**
 * Finds the booking of a payment, the way back from the child to its parent. A payment of the producer cannot name
 * its booking, so the consumer supplies this lookup. It keeps the bookings it was told about by the id of their
 * deposit, where an application would ask its repository.
 */
public final class BookingLookups {

    private final Map<String, Booking> bookings = new HashMap<>();

    /** Remembers {@code booking} as the booking of its deposit. */
    public void register(Booking booking) {
        bookings.put(booking.deposit().orElseThrow().id(), booking);
    }

    @GraphLookup
    public Optional<Booking> bookingOf(Payment payment) {
        return Optional.ofNullable(bookings.get(payment.id()));
    }
}
