package io.github.graphmaquina.example.loan;

import java.util.concurrent.atomic.AtomicInteger;

/** A reservations service under the test's control: it answers {@link #answer}, counting the calls, or is down. */
final class FakeReservations implements Reservations {

    private final AtomicInteger calls = new AtomicInteger();
    ReservationSummary answer = new ReservationSummary(0, false);
    boolean down;

    @Override
    public ReservationSummary summaryFor(String loanId) {
        calls.incrementAndGet();
        if (down) {
            throw new ReservationsUnavailable("the reservations service is down");
        }
        return answer;
    }

    /** How many times a guard asked the service. */
    int calls() {
        return calls.get();
    }
}
