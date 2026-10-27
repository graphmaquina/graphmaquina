package book;

import static book.BookingStatus.CANCELLED;
import static book.BookingStatus.CONFIRMED;
import static book.BookingStatus.DEPOSIT;
import static book.BookingStatus.REQUESTED;
import static book.BookingTransition.BOOK;
import static book.BookingTransition.DEPOSIT_DECLINED;
import static book.BookingTransition.DEPOSIT_PAID;

import io.github.graphmaquina.annotation.GenerateGraphApi;
import io.github.graphmaquina.annotation.GraphPeers;
import io.github.graphmaquina.api.Machine;
import io.github.graphmaquina.api.Outcome;
import pay.PaymentStatus;
import pay.Payments;

/**
 * The booking machine of the consumer: {@code DEPOSIT} holds a payment of {@link Payments}, a machine of the
 * producer's jar, and the terminal state that payment reaches selects the booking's exit. The binding is declared
 * here, on the parent's side: the producer knows no booking.
 */
@GenerateGraphApi
@GraphPeers(Payments.class)
@BookingsGraph.Definition(
    initial = REQUESTED,
    terminals = {CONFIRMED, CANCELLED},
    state = BookingRefs.Status.class,
    id = BookingRefs.Id.class)
@BookingsGraph.StatePolicy(state = REQUESTED, otherwise = "a booking is booked first")
@BookingsGraph.StatePolicy(state = DEPOSIT, otherwise = "the deposit payment decides")
@BookingsGraph.Transition(id = DEPOSIT_PAID, from = DEPOSIT, to = CONFIRMED)
@BookingsGraph.Transition(id = DEPOSIT_DECLINED, from = DEPOSIT, to = CANCELLED)
@BookingsPayments.Submachine(
    state = DEPOSIT,
    child = BookingRefs.Deposit.class,
    parentLookup = BookingLookupsRefs.BookingOf.class,
    exits = {
        @BookingsPayments.Exit(on = PaymentStatus.CAPTURED, transition = DEPOSIT_PAID),
        @BookingsPayments.Exit(on = PaymentStatus.DECLINED, transition = DEPOSIT_DECLINED)})
public interface Bookings extends Machine<Booking, BookingStatus, BookingTransition> {

    @BookingsGraph.Transition(id = BOOK, from = REQUESTED, to = DEPOSIT)
    Outcome<Booking, BookingStatus, BookingTransition> book(Booking booking);
}
