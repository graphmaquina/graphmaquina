package io.github.graphmaquina.example.loan;

import io.github.graphmaquina.annotation.GraphGuard;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.MemoKey;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The guards of the loan's edges. The due-date guards read the instant of the call from {@code ctx.now()}, on the
 * clock the set was built with. The two reservation guards share one answer of the reservations service for each loan
 * through {@code ctx.memo}, so a check asks the service once, and both propagate {@link ReservationsUnavailable}, so
 * the caller receives the service's failure as itself.
 */
@RequiredArgsConstructor
public final class LoanGuards {

    /** The reservations of the loan's book, kept for that loan for the rest of the request once a guard read them. */
    public static final MemoKey<ReservationSummary> RESERVATIONS = MemoKey.named("reservations");

    @NonNull
    private final Reservations reservations;

    @GraphGuard
    public GuardResult beforeDue(GuardContext<Loan, LoanStatus, LoanTransition> ctx) {
        return ctx.now().isBefore(ctx.entity().dueAt())
                ? GuardResult.pass() : GuardResult.reject("the loan is past due");
    }

    @GraphGuard
    public GuardResult pastDue(GuardContext<Loan, LoanStatus, LoanTransition> ctx) {
        return ctx.now().isAfter(ctx.entity().dueAt()) ? GuardResult.pass() : GuardResult.reject("the loan is not due");
    }

    @GraphGuard(propagates = ReservationsUnavailable.class)
    public GuardResult noWaitingReader(GuardContext<Loan, LoanStatus, LoanTransition> ctx) {
        return summary(ctx).waitingReaders() == 0
                ? GuardResult.pass() : GuardResult.reject("a reader is waiting for the book");
    }

    @GraphGuard(propagates = ReservationsUnavailable.class)
    public GuardResult notHeldForCourse(GuardContext<Loan, LoanStatus, LoanTransition> ctx) {
        return summary(ctx).heldForCourse() ? GuardResult.reject("the book is held for a course") : GuardResult.pass();
    }

    private ReservationSummary summary(GuardContext<Loan, LoanStatus, LoanTransition> ctx) {
        return ctx.memo(RESERVATIONS, () -> reservations.summaryFor(ctx.entity().id()));
    }
}
