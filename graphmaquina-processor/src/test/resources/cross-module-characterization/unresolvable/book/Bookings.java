package book;

import io.github.graphmaquina.annotation.GenerateGraphApi;
import io.github.graphmaquina.annotation.GraphPeers;
import io.github.graphmaquina.api.Machine;
import io.github.graphmaquina.api.Outcome;
import static book.BookingStatus.*;
import static book.BookingTransition.*;

/** Bookings, whose declared peer is a class that exists nowhere. */
@GenerateGraphApi
@GraphPeers(nowhere.Missing.class)
@BookingsGraph.Definition(initial = REQUESTED, terminals = {CONFIRMED, CANCELLED},
    state = BookingRefs.Status.class, id = BookingRefs.Id.class)
@BookingsGraph.StatePolicy(state = REQUESTED, otherwise = "a booking is requested first")
@BookingsGraph.StatePolicy(state = DEPOSIT, otherwise = "the deposit decides")
@BookingsGraph.Transition(id = DEPOSIT_PAID, from = DEPOSIT, to = CONFIRMED)
@BookingsGraph.Transition(id = DECLINE, from = DEPOSIT, to = CANCELLED)
public interface Bookings extends Machine<Booking, BookingStatus, BookingTransition> {
    @BookingsGraph.Transition(id = BOOK, from = REQUESTED, to = DEPOSIT)
    Outcome<Booking, BookingStatus, BookingTransition> book(Booking booking);
}
