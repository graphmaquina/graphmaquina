package io.github.graphmaquina.example.loan;

import static io.github.graphmaquina.example.loan.LoanStatus.BORROWED;
import static io.github.graphmaquina.example.loan.LoanStatus.LOST;
import static io.github.graphmaquina.example.loan.LoanStatus.OVERDUE;
import static io.github.graphmaquina.example.loan.LoanStatus.RETURNED;
import static io.github.graphmaquina.example.loan.LoanTransition.MARK_OVERDUE;
import static io.github.graphmaquina.example.loan.LoanTransition.REPORT_LOST;
import static io.github.graphmaquina.example.loan.LoanTransition.REQUEST_EXTENSION;
import static io.github.graphmaquina.example.loan.LoanTransition.RETURN;

import io.github.graphmaquina.annotation.GenerateGraphApi;
import io.github.graphmaquina.api.Eligibility;
import io.github.graphmaquina.api.Machine;
import io.github.graphmaquina.api.Outcome;

/**
 * The loan machine. REQUEST_EXTENSION is a gate: a borrower asks through {@link #canRequestExtension} whether an
 * extension would be granted, and nothing ever executes it. REPORT_LOST is external: the inventory system writes LOST
 * itself, so the edge only tells the graph that LOST is reachable. The two closed states share one policy. The
 * processor generates LoansRuntime.
 */
@GenerateGraphApi
@LoansGraph.Definition(initial = BORROWED, terminals = {RETURNED, LOST},
        state = LoanRefs.Status.class, id = LoanRefs.Id.class)
@LoansGraph.StatePolicy(state = BORROWED, otherwise = Loans.BORROWED_POLICY)
@LoansGraph.StatePolicy(state = OVERDUE, otherwise = Loans.OVERDUE_POLICY)
@LoansGraph.StatePolicy(state = {RETURNED, LOST}, otherwise = Loans.CLOSED_POLICY)
@LoansGraph.Transition(id = REQUEST_EXTENSION, from = BORROWED, to = BORROWED, gate = true,
        guards = {LoanGuardsRefs.BeforeDue.class, LoanGuardsRefs.NoWaitingReader.class,
                LoanGuardsRefs.NotHeldForCourse.class})
@LoansGraph.Transition(id = REPORT_LOST, from = {BORROWED, OVERDUE}, to = LOST, external = true)
public interface Loans extends Machine<Loan, LoanStatus, LoanTransition> {

    /** Marks a loan overdue once the set's clock reads a time past its due date. */
    @LoansGraph.Transition(id = MARK_OVERDUE, from = BORROWED, to = OVERDUE, guards = LoanGuardsRefs.PastDue.class)
    Outcome<Loan, LoanStatus, LoanTransition> markOverdue(Loan loan);

    /** Closes a borrowed or an overdue loan when the book comes back. */
    @LoansGraph.Transition(id = RETURN, from = {BORROWED, OVERDUE}, to = RETURNED)
    Outcome<Loan, LoanStatus, LoanTransition> returnBook(Loan loan);

    /** Whether an extension would be granted now: the guards of the gate, which nothing executes. */
    @LoansGraph.Check(id = REQUEST_EXTENSION)
    Eligibility<Loan, LoanStatus, LoanTransition> canRequestExtension(Loan loan);

    String BORROWED_POLICY = "a borrowed book is returned, marked overdue or reported lost";
    String OVERDUE_POLICY = "an overdue book is returned or reported lost";
    String CLOSED_POLICY = "the loan is closed";
}
