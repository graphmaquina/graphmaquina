package io.github.graphmaquina.example.billing;

import io.github.graphmaquina.annotation.GraphLookup;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.NonNull;

/**
 * Finds the parent of a payment, the booking or the subscription it was registered for. An in-memory map stands in
 * for a repository query. The set's builder takes the instance, since both lookups are instance methods.
 */
public final class BillingLookups {

    private final Map<String, Booking> bookings = new ConcurrentHashMap<>();
    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();

    /** Registers the deposit and the balance of {@code booking}. */
    public void register(@NonNull Booking booking) {
        bookings.put(booking.deposit().orElseThrow().id(), booking);
        bookings.put(booking.balance().orElseThrow().id(), booking);
    }

    /** Registers the renewal payment of {@code subscription}. */
    public void register(@NonNull Subscription subscription) {
        subscriptions.put(subscription.renewal().orElseThrow().id(), subscription);
    }

    @GraphLookup
    public Optional<Booking> bookingOf(Payment payment) {
        return Optional.ofNullable(bookings.get(payment.id()));
    }

    @GraphLookup
    public Optional<Subscription> subscriptionOf(Payment payment) {
        return Optional.ofNullable(subscriptions.get(payment.id()));
    }
}
