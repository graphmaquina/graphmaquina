package app;

import book.Booking;
import book.BookingLookups;
import java.util.List;
import pay.Payment;
import shop.Order;
import shop.OrderLookups;
import shop.OrderStatus;
import stock.Item;

/**
 * Runs both scenarios over one {@link Store}: a booking whose deposit is captured, then an order whose items are
 * packed one by one.
 */
public final class Scenario {

    private Scenario() {
    }

    /** Prints what {@link #run()} reports. */
    public static void main(String[] args) {
        System.out.println(run());
    }

    /**
     * The booking and its deposit once the deposit is captured, then the order after its first item is packed and
     * after its last: {@code CONFIRMED CAPTURED / PAID SHIPPED}.
     */
    public static String run() {
        BookingLookups bookingLookups = new BookingLookups();
        OrderLookups orderLookups = new OrderLookups();
        try (Store store = StoreRuntime.builder(bookingLookups, orderLookups).build()) {
            return deposit(store, bookingLookups) + " / " + packing(store, orderLookups);
        }
    }

    /** Books a booking, captures its deposit, and reports the states of both. */
    private static String deposit(Store store, BookingLookups bookingLookups) {
        Payment deposit = new Payment("deposit-1");
        Booking booking = new Booking("booking-1", deposit);
        bookingLookups.register(booking);
        store.bookings().book(booking);
        store.payments().capture(deposit);
        return booking.status() + " " + deposit.status();
    }

    /** Pays an order of two items, packs them one by one, and reports the order's state after each. */
    private static String packing(Store store, OrderLookups orderLookups) {
        Item first = new Item("item-1");
        Item last = new Item("item-2");
        Order order = new Order("order-1", List.of(first, last));
        orderLookups.register(order);
        store.orders().pay(order);
        store.items().pack(first);
        OrderStatus afterFirst = order.status();
        store.items().pack(last);
        return afterFirst + " " + order.status();
    }
}
