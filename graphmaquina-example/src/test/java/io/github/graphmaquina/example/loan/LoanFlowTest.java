package io.github.graphmaquina.example.loan;

import static io.github.graphmaquina.example.loan.LoanStatus.BORROWED;
import static io.github.graphmaquina.example.loan.LoanStatus.LOST;
import static io.github.graphmaquina.example.loan.LoanStatus.OVERDUE;
import static io.github.graphmaquina.example.loan.LoanStatus.RETURNED;
import static io.github.graphmaquina.example.loan.LoanTransition.REPORT_LOST;
import static io.github.graphmaquina.example.loan.LoanTransition.REQUEST_EXTENSION;
import static io.github.graphmaquina.example.loan.LoanTransition.RETURN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.Eligibility;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.RejectionKind;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.runtime.StateMachines;
import java.time.Instant;
import java.time.InstantSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The loan machine of the guide pages on edge kinds and guard evidence: a borrower asks whether an extension would be
 * granted through the gate's check, which reads the reservations service once for its two guards and lets that
 * service's failure reach the caller as itself; the set's clock decides when a loan is past due; a loss is reported by
 * another system, so the machine never takes it; and the selector runs a return.
 */
class LoanFlowTest {

    private static final Instant DUE = Instant.parse("2026-12-01T00:00:00Z");

    private final FakeReservations reservations = new FakeReservations();
    private LoanMachines machines = machinesAt(DUE.minusSeconds(3600));

    private LoanMachines machinesAt(Instant now) {
        return LoanMachinesRuntime.builder(new LoanGuards(reservations)).clock(InstantSource.fixed(now)).build();
    }

    @AfterEach
    void close() {
        machines.close();
    }

    @Test
    void theGateIsCheckedNotExecuted() {
        Loans loans = machines.loans();
        Loan loan = new Loan("l1", DUE);

        assertTrue(loans.canRequestExtension(loan).isEligible());
        assertThrows(IllegalArgumentException.class, () -> loans.execute(loan, REQUEST_EXTENSION));
        assertEquals(BORROWED, loan.status());
    }

    /** Both reservation guards run, the second refusing, and the service answered once for the two of them. */
    @Test
    void theReservationsAreReadOnce() {
        reservations.answer = new ReservationSummary(0, true);

        Eligibility.Ineligible<Loan, LoanStatus, LoanTransition> refused =
                machines.loans().canRequestExtension(new Loan("l2", DUE)).asIneligible().orElseThrow();

        assertEquals("the book is held for a course", refused.reason());
        assertEquals(1, reservations.calls());
    }

    @Test
    void anUnavailableReservationsServiceReachesTheCallerAsItself() {
        reservations.down = true;

        ReservationsUnavailable thrown = assertThrows(ReservationsUnavailable.class,
                () -> machines.loans().canRequestExtension(new Loan("l3", DUE)));

        assertEquals("the reservations service is down", thrown.getMessage());
    }

    @Test
    void markOverdueUsesTheSetsClock() {
        Loan loan = new Loan("l4", DUE);

        assertEquals(RejectionKind.GUARD, machines.loans().markOverdue(loan).asRejected().orElseThrow().kind());
        machines.close();
        machines = machinesAt(DUE.plusSeconds(3600));

        assertTrue(machines.loans().markOverdue(loan).isApplied());
        assertEquals(OVERDUE, loan.status());
    }

    @Test
    void lostIsReachedOnlyFromOutside() {
        StateMachine<Loan, LoanStatus, LoanTransition> machine =
                StateMachines.from(LoansRuntime.graph(new LoanGuards(reservations)))
                        .state(Loan::status, Loan::setStatus).identity(Loan::id).build();
        Loan borrowed = new Loan("l5", DUE);
        Loan lost = new Loan("l6", DUE);
        lost.setStatus(LOST);

        Outcome.Rejected<Loan, LoanStatus, LoanTransition> reported =
                machine.execute(borrowed, REPORT_LOST).asRejected().orElseThrow();
        Outcome.Rejected<Loan, LoanStatus, LoanTransition> closed =
                machines.loans().returnBook(lost).asRejected().orElseThrow();

        assertEquals(RejectionKind.NO_TRANSITION, reported.kind());
        assertEquals(Loans.BORROWED_POLICY, reported.reason());
        assertEquals(Loans.CLOSED_POLICY, closed.reason());
    }

    @Test
    void theSelectorRunsReturn() {
        Loan loan = new Loan("l7", DUE);

        Outcome<Loan, LoanStatus, LoanTransition> outcome = machines.loans().execute(loan, RETURN);

        assertTrue(outcome.isApplied());
        assertSame(RETURNED, loan.status());
    }
}
