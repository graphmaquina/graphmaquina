package book;

import pay.Payment;

/** Books a booking, whose deposit the payments then capture, and reports both states. */
public final class Scenario {
    private Scenario() { }
    public static Object run() {
        try (Shop shop = ShopRuntime.builder().build()) {
            Payment deposit = new Payment("deposit");
            Booking booking = new Booking("booking", deposit);
            BookingLookups.register(booking);
            shop.bookings().book(booking);
            shop.payments().capture(deposit);
            return booking.status() + " " + deposit.status();
        }
    }
}
