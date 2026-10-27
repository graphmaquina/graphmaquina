package book;

import io.github.graphmaquina.annotation.GenerateGraphApi;
import io.github.graphmaquina.annotation.GraphPeers;
import io.github.graphmaquina.api.Machine;
import io.github.graphmaquina.api.Outcome;
import pay.PaymentStatus;
import pay.Payments;
import static book.BookingStatus.*;
import static book.BookingTransition.*;

/** Bookings. */
@GenerateGraphApi
@GraphPeers(Payments.class)
@BookingsGraph.Definition(initial = REQUESTED, terminals = {CONFIRMED, CANCELLED},
    state = BookingRefs.Status.class, id = BookingRefs.Id.class)
@BookingsGraph.StatePolicy(state = REQUESTED, otherwise = "a booking is requested first")
@BookingsGraph.StatePolicy(state = DEPOSIT, otherwise = "the deposit decides")
@BookingsGraph.Transition(id = DEPOSIT_PAID, from = DEPOSIT, to = CONFIRMED)
@BookingsGraph.Transition(id = DECLINE, from = DEPOSIT, to = CANCELLED)
@BookingsPayments.Submachine(state = DEPOSIT, child = BookingRefs.Deposit.class,
    parentLookup = BookingLookupsRefs.DepositOf.class,
    exits = {@BookingsPayments.Exit(on = PaymentStatus.CAPTURED, transition = DEPOSIT_PAID),
             @BookingsPayments.Exit(on = PaymentStatus.DECLINED, transition = DECLINE)})
public interface Bookings extends Machine<Booking, BookingStatus, BookingTransition> {
    @BookingsGraph.Transition(id = BOOK, from = REQUESTED, to = DEPOSIT)
    Outcome<Booking, BookingStatus, BookingTransition> book(Booking booking);
}
