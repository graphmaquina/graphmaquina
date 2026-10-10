package io.github.graphmaquina.example.billing;

import static io.github.graphmaquina.example.billing.BookingStatus.BALANCE;
import static io.github.graphmaquina.example.billing.BookingStatus.CANCELLED;
import static io.github.graphmaquina.example.billing.BookingStatus.CONFIRMED;
import static io.github.graphmaquina.example.billing.BookingStatus.DEPOSIT;
import static io.github.graphmaquina.example.billing.BookingStatus.REQUESTED;

import io.github.graphmaquina.annotation.GenerateGraphApi;
import io.github.graphmaquina.annotation.GraphPeers;
import io.github.graphmaquina.api.Machine;
import io.github.graphmaquina.api.Outcome;

/**
 * The booking machine: {@code DEPOSIT} and {@code BALANCE} each hold a payment of {@link Payments}, the same machine
 * bound twice. The terminal state each payment reaches selects the exit of its state, and {@code CANCEL} interrupts
 * either one. The processor generates BookingsRuntime.
 *
 * <p>Exits are declared on the interface, not on operations: an exit runs only when its payment ends, so no
 * operation may declare one.</p>
 */
@GenerateGraphApi
@GraphPeers(Payments.class)
@BookingsGraph.Definition(initial = REQUESTED, terminals = {CONFIRMED, CANCELLED},
        state = BookingRefs.Status.class, id = BookingRefs.Id.class)
@BookingsGraph.StatePolicy(state = REQUESTED, otherwise = "a booking is booked first")
@BookingsGraph.StatePolicy(state = DEPOSIT,   otherwise = "the deposit payment decides")
@BookingsGraph.StatePolicy(state = BALANCE,   otherwise = "the balance payment decides")
@BookingsGraph.Transition(id = BookingTransition.DEPOSIT_PAID, from = DEPOSIT, to = BALANCE,
        action = BookingActionsRefs.HoldRoom.class, onFailure = BookingTransition.NO_ROOM)
@BookingsGraph.Transition(id = BookingTransition.NO_ROOM, from = DEPOSIT, to = CANCELLED)
@BookingsGraph.Transition(id = BookingTransition.DEPOSIT_DECLINED, from = DEPOSIT, to = CANCELLED)
@BookingsGraph.Transition(id = BookingTransition.BALANCE_PAID, from = BALANCE, to = CONFIRMED)
@BookingsGraph.Transition(id = BookingTransition.BALANCE_DECLINED, from = BALANCE, to = CANCELLED)
@BookingsGraph.Transition(id = BookingTransition.PAYMENT_VOIDED, from = {DEPOSIT, BALANCE}, to = CANCELLED)
@BookingsPayments.Submachine(state = DEPOSIT, child = BookingRefs.Deposit.class,
        parentLookup = BillingLookupsRefs.BookingOf.class,
        exits = {@BookingsPayments.Exit(on = PaymentStatus.CAPTURED, transition = BookingTransition.DEPOSIT_PAID),
                 @BookingsPayments.Exit(on = PaymentStatus.DECLINED, transition = BookingTransition.DEPOSIT_DECLINED),
                 @BookingsPayments.Exit(on = PaymentStatus.VOIDED, transition = BookingTransition.PAYMENT_VOIDED)})
@BookingsPayments.Submachine(state = BALANCE, child = BookingRefs.Balance.class,
        parentLookup = BillingLookupsRefs.BookingOf.class,
        exits = {@BookingsPayments.Exit(on = PaymentStatus.CAPTURED, transition = BookingTransition.BALANCE_PAID),
                 @BookingsPayments.Exit(on = PaymentStatus.DECLINED, transition = BookingTransition.BALANCE_DECLINED),
                 @BookingsPayments.Exit(on = PaymentStatus.VOIDED, transition = BookingTransition.PAYMENT_VOIDED)})
public interface Bookings extends Machine<Booking, BookingStatus, BookingTransition> {

    @BookingsGraph.Transition(id = BookingTransition.BOOK, from = REQUESTED, to = DEPOSIT)
    Outcome<Booking, BookingStatus, BookingTransition> book(Booking booking);

    @BookingsGraph.Transition(id = BookingTransition.CANCEL, from = {DEPOSIT, BALANCE}, to = CANCELLED,
            action = BookingActionsRefs.Cancel.class)
    Outcome<Booking, BookingStatus, BookingTransition> cancel(Booking booking);
}
