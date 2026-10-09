package io.github.graphmaquina.example.billing;

import static io.github.graphmaquina.test.GraphAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.test.AsyncAssertions;
import io.github.graphmaquina.test.StateSnapshot;
import io.github.graphmaquina.test.probe.ActionCall;
import io.github.graphmaquina.test.probe.ControlledAsyncAction;
import io.github.graphmaquina.test.probe.PendingInvocation;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * The exit {@code DEPOSIT_PAID} runs an asynchronous action, so the payment machine, whose calls run that exit, has
 * the set's shared timeout too. A {@link ControlledAsyncAction} stands in for the desk and holds the exit open without
 * a sleep: neither entity is written while it waits, and both are committed together once the test answers.
 */
class AsyncExitTest {

    @Test
    void theDepositsExitWaitsForTheDeskAndCommitsBothEntitiesTogether() {
        Duration testBudget = Duration.ofSeconds(10);
        BillingLookups lookups = new BillingLookups();
        ControlledAsyncAction<Booking, BookingStatus, BookingTransition> holdRoom = ControlledAsyncAction.create();
        Booking booking = new Booking("b1", new Payment("b1-deposit"), new Payment("b1-balance"));
        Payment deposit = booking.deposit().orElseThrow();
        lookups.register(booking);
        try (BillingMachines billing = BillingMachinesRuntime.builder(Duration.ofSeconds(5),
                new BookingActions(holdRoom::process), lookups).build(); holdRoom) {
            billing.bookings().book(booking);
            StateSnapshot before = StateSnapshot.builder()
                    .track(BookingsRuntime.KEY, booking, Booking::status)
                    .track(PaymentsRuntime.KEY, deposit, Payment::status)
                    .capture();

            CompletableFuture<Outcome<Payment, PaymentStatus, PaymentTransition>> captured =
                    billing.payments().async(() -> billing.payments().capture(deposit));
            PendingInvocation<ActionCall<Booking, BookingStatus, BookingTransition>, ActionOutcome> pending =
                    holdRoom.awaitInvocation(testBudget);
            assertEquals(BookingTransition.DEPOSIT_PAID, pending.call().transition());
            before.assertUnchanged();

            pending.complete(ActionOutcome.ok());
            AsyncAssertions.await(captured, testBudget).isApplied().movedTo(PaymentStatus.CAPTURED)
                    .records().forEntity(BookingsRuntime.KEY, booking).single()
                    .hasTransition(BookingTransition.DEPOSIT_PAID).to(BookingStatus.BALANCE).hasDepth(1);
            assertThat(booking, Booking::status).isIn(BookingStatus.BALANCE);
        }
    }
}
