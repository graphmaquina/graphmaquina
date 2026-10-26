package desk;

import book.Booking;
import book.BookingLookups;
import pay.Payment;

/** Runs the deposit scenario over a {@link Desk}, whose machines come from two jars. */
public final class DeskScenario {

    private DeskScenario() {
    }

    /** Books a booking of book.jar, captures its deposit, a payment of pay.jar, and reports the states of both. */
    public static String run() {
        BookingLookups lookups = new BookingLookups();
        try (Desk desk = DeskRuntime.builder(lookups).build()) {
            Payment deposit = new Payment("deposit-1");
            Booking booking = new Booking("booking-1", deposit);
            lookups.register(booking);
            desk.bookings().book(booking);
            desk.payments().capture(deposit);
            return booking.status() + " " + deposit.status();
        }
    }
}
