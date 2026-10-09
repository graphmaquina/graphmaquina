package io.github.graphmaquina.example.billing;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.TransitionContext;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Answers at once: it holds a room until {@link #full()} is called, and fails with "no room left" after that. */
final class FakeRoomDesk implements RoomDesk {

    private volatile boolean full;

    FakeRoomDesk full() {
        full = true;
        return this;
    }

    @Override
    public CompletionStage<ActionOutcome> holdRoom(
            TransitionContext<Booking, BookingStatus, BookingTransition> context) {
        return CompletableFuture.completedFuture(full ? ActionOutcome.failed("no room left") : ActionOutcome.ok());
    }
}
