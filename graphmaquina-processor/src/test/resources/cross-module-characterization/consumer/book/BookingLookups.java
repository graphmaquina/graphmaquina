package book;

import io.github.graphmaquina.annotation.GraphLookup;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import pay.Payment;

/** Lookups. */
public final class BookingLookups {
    private static final Map<String, Booking> DEPOSITS = new HashMap<>();
    public static void register(Booking booking) {
        DEPOSITS.put(booking.deposit().orElseThrow().id(), booking);
    }
    @GraphLookup public static Optional<Booking> depositOf(Payment payment) {
        return Optional.ofNullable(DEPOSITS.get(payment.id()));
    }
}
