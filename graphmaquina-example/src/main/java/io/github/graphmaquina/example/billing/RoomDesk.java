package io.github.graphmaquina.example.billing;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.TransitionContext;
import java.util.concurrent.CompletionStage;

/**
 * The hotel's desk, asked to hold a room once the deposit of a booking is paid. A real desk answers from its own
 * thread; a failed answer sends the booking through {@code NO_ROOM}.
 */
@FunctionalInterface
public interface RoomDesk {

    CompletionStage<ActionOutcome> holdRoom(TransitionContext<Booking, BookingStatus, BookingTransition> context);
}
