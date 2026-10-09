package io.github.graphmaquina.example.billing;

import static io.github.graphmaquina.test.ExitExpectation.exit;
import static io.github.graphmaquina.test.GraphAssertions.assertThat;

import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.test.TransitionRecorder;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A booking pays through two bindings of the same payment machine: each payment starts when the booking enters its
 * state, and the terminal state it reaches selects the booking's exit within the payment's own call.
 */
class BookingFlowTest {

    final BillingLookups lookups = new BillingLookups();
    final FakeRoomDesk desk = new FakeRoomDesk();
    final TransitionRecorder recorder = TransitionRecorder.create();
    final BillingMachines billing = BillingMachinesRuntime.builder(Duration.ofSeconds(10), new BookingActions(desk),
                    lookups)
            .observer(recorder)
            .build();
    final Bookings bookings = billing.bookings();
    final Payments payments = billing.payments();

    @AfterEach
    void close() {
        billing.close();
    }

    @Test
    void theBookingBindsThePaymentMachineInBothPaymentStates() {
        assertThat(bookings.definition())
                .hasNoSubmachine(BookingStatus.REQUESTED)
                .submachine(BookingStatus.DEPOSIT, PaymentsRuntime.KEY)
                .hasExitsExactly(exit(PaymentStatus.CAPTURED, BookingTransition.DEPOSIT_PAID),
                        exit(PaymentStatus.DECLINED, BookingTransition.DEPOSIT_DECLINED),
                        exit(PaymentStatus.VOIDED, BookingTransition.PAYMENT_VOIDED));
        assertThat(bookings.definition())
                .submachine(BookingStatus.BALANCE, PaymentsRuntime.KEY)
                .hasExit(PaymentStatus.CAPTURED, BookingTransition.BALANCE_PAID);
    }

    /**
     * Capturing the deposit exits to {@code BALANCE} in the same call, which starts the balance one level deeper.
     * Each exit belongs to the call of its payment: the recorder sees three calls.
     */
    @Test
    void aBookingPaysItsDepositThenItsBalance() {
        Booking booking = booking("b1");
        Payment deposit = booking.deposit().orElseThrow();
        Payment balance = booking.balance().orElseThrow();

        assertThat(bookings.book(booking)).isApplied().movedTo(BookingStatus.DEPOSIT)
                .starts().forChild(PaymentsRuntime.KEY, deposit).single()
                .startedAt(PaymentStatus.AUTHORIZING)
                .byParent(BookingsRuntime.KEY, booking, BookingStatus.DEPOSIT)
                .atDepth(0);

        Outcome<Payment, PaymentStatus, PaymentTransition> depositPaid = payments.capture(deposit);
        assertThat(depositPaid).isApplied().movedTo(PaymentStatus.CAPTURED)
                .records().forEntity(BookingsRuntime.KEY, booking).single()
                .hasTransition(BookingTransition.DEPOSIT_PAID).to(BookingStatus.BALANCE).hasDepth(1);
        assertThat(depositPaid).starts().forChild(PaymentsRuntime.KEY, balance).single()
                .startedAt(PaymentStatus.AUTHORIZING)
                .byParent(BookingsRuntime.KEY, booking, BookingStatus.BALANCE)
                .atDepth(1);

        assertThat(payments.capture(balance)).isApplied()
                .records().forEntity(BookingsRuntime.KEY, booking).single()
                .hasTransition(BookingTransition.BALANCE_PAID).to(BookingStatus.CONFIRMED).hasDepth(1);
        assertThat(booking, Booking::status).isIn(BookingStatus.CONFIRMED);

        recorder.assertRecorded()
                .hasSize(3)
                .hasKeysExactly(BookingsRuntime.KEY, PaymentsRuntime.KEY, PaymentsRuntime.KEY);
    }

    /**
     * {@code CANCEL} leaves {@code DEPOSIT} by an edge of the booking's own, and its action voids the deposit while the
     * booking is still leaving, so the voided deposit runs no exit. Once the booking is cancelled the deposit is
     * detached: capturing it is refused before any edge is looked up, and nothing moves.
     */
    @Test
    void aCancelledBookingVoidsItsDepositAndDetachesIt() {
        Booking booking = booking("b2");
        Payment deposit = booking.deposit().orElseThrow();
        bookings.book(booking);

        Outcome<Booking, BookingStatus, BookingTransition> cancelled = bookings.cancel(booking);
        assertThat(cancelled).isApplied().movedTo(BookingStatus.CANCELLED)
                .records().forEntity(PaymentsRuntime.KEY, deposit).single()
                .hasTransition(PaymentTransition.VOID).to(PaymentStatus.VOIDED).hasDepth(1);
        assertThat(cancelled).records().forEntity(BookingsRuntime.KEY, booking).single()
                .hasTransition(BookingTransition.CANCEL);

        assertThat(payments.capture(deposit)).isRejected()
                .isDetached()
                .hasReason("the parent is not in DEPOSIT or BALANCE");
        assertThat(deposit, Payment::status).isIn(PaymentStatus.VOIDED);
        assertThat(booking, Booking::status).isIn(BookingStatus.CANCELLED);
    }

    /**
     * The exit {@code DEPOSIT_PAID} holds a room, and a desk with none left fails its action: the booking takes the
     * route {@code NO_ROOM} and the whole call commits. The payment's own outcome is still applied.
     */
    @Test
    void aDeskWithNoRoomRoutesTheDepositsExitThroughNoRoom() {
        desk.full();
        Booking booking = booking("b3");
        Payment deposit = booking.deposit().orElseThrow();
        bookings.book(booking);

        Outcome<Payment, PaymentStatus, PaymentTransition> depositPaid = payments.capture(deposit);
        assertThat(depositPaid).isApplied().movedTo(PaymentStatus.CAPTURED)
                .records().forEntity(BookingsRuntime.KEY, booking).single()
                .hasTransition(BookingTransition.NO_ROOM).to(BookingStatus.CANCELLED).hasDepth(1);
        assertThat(depositPaid).starts().isEmpty();
        assertThat(booking, Booking::status).isIn(BookingStatus.CANCELLED);
    }

    private Booking booking(String id) {
        Booking booking = new Booking(id, new Payment(id + "-deposit"), new Payment(id + "-balance"));
        lookups.register(booking);
        return booking;
    }
}
