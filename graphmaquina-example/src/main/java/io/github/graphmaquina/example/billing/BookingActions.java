package io.github.graphmaquina.example.billing;

import io.github.graphmaquina.annotation.GraphAction;
import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.TransitionContext;
import java.util.concurrent.CompletionStage;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** The code behind the booking edges. A plain class: {@link Bookings} says which method serves which edge. */
@RequiredArgsConstructor
public final class BookingActions {

    @NonNull
    private final RoomDesk desk;

    /** The action of the exit {@code DEPOSIT_PAID}: the desk holds a room, or the exit takes {@code NO_ROOM}. */
    @GraphAction
    public CompletionStage<ActionOutcome> holdRoom(TransitionContext<Booking, BookingStatus, BookingTransition> ctx) {
        return desk.holdRoom(ctx);
    }

    /**
     * Voids the payment of the state the booking leaves: the deposit from {@code DEPOSIT}, the balance from
     * {@code BALANCE}. The booking is leaving that state, so the payment is still bound to it and the cascade runs.
     * The booking is in flight, so the voided payment runs no exit: the booking ends {@code CANCELLED} by its own
     * edge.
     */
    @GraphAction
    public ActionOutcome cancel(TransitionContext<Booking, BookingStatus, BookingTransition> ctx) {
        Booking booking = ctx.entity();
        Payment payment = ctx.from() == BookingStatus.DEPOSIT ? booking.deposit().orElseThrow()
                : booking.balance().orElseThrow();
        ctx.cascade(Payments.class, payment, PaymentTransition.VOID);
        return ActionOutcome.ok();
    }
}
