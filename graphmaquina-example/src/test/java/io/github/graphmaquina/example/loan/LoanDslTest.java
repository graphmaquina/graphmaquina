package io.github.graphmaquina.example.loan;

import static io.github.graphmaquina.example.loan.LoanStatus.BORROWED;
import static io.github.graphmaquina.example.loan.LoanStatus.LOST;
import static io.github.graphmaquina.example.loan.LoanStatus.OVERDUE;
import static io.github.graphmaquina.example.loan.LoanStatus.RETURNED;
import static io.github.graphmaquina.example.loan.LoanTransition.MARK_OVERDUE;
import static io.github.graphmaquina.example.loan.LoanTransition.REPORT_LOST;
import static io.github.graphmaquina.example.loan.LoanTransition.REQUEST_EXTENSION;
import static io.github.graphmaquina.example.loan.LoanTransition.RETURN;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.test.snapshot.GraphSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The loan's graph written with the DSL in place of the annotations of {@link Loans}: a gate with three guards, two of
 * which propagate the reservations service's failure, an external edge from two states, and one policy for the two
 * closed states. Its structural snapshot equals that of the annotated graph.
 */
class LoanDslTest {

    static GraphDefinition<Loan, LoanStatus, LoanTransition> graph(LoanGuards guards) {
        List<Class<? extends Throwable>> unavailable = List.of(ReservationsUnavailable.class);
        return StateGraph.define(MachineKey.of(Loan.class, LoanStatus.class, LoanTransition.class))
                .initial(BORROWED).terminal(RETURNED).terminal(LOST)
                .statePolicy(BORROWED, Loans.BORROWED_POLICY)
                .statePolicy(OVERDUE, Loans.OVERDUE_POLICY)
                .statePolicy(RETURNED, Loans.CLOSED_POLICY)
                .statePolicy(LOST, Loans.CLOSED_POLICY)
                .transition(REQUEST_EXTENSION, BORROWED, BORROWED).gate()
                    .guard("beforeDue", guards::beforeDue)
                    .guard("noWaitingReader", guards::noWaitingReader, unavailable)
                    .guard("notHeldForCourse", guards::notHeldForCourse, unavailable)
                    .endTransition()
                .transition(MARK_OVERDUE, BORROWED, OVERDUE).guard("pastDue", guards::pastDue).endTransition()
                .transition(RETURN, BORROWED, RETURNED).endTransition()
                .transition(RETURN, OVERDUE, RETURNED).endTransition()
                .transition(REPORT_LOST, BORROWED, LOST).external().endTransition()
                .transition(REPORT_LOST, OVERDUE, LOST).external().endTransition()
                .build();
    }

    @Test
    void theDslDeclaresTheAnnotatedGraph() {
        GraphDefinition<Loan, LoanStatus, LoanTransition> dsl = graph(new LoanGuards(new FakeReservations()));

        assertEquals(GraphSnapshot.capture(LoansRuntime.structure()).fields(), GraphSnapshot.capture(dsl).fields());
    }
}
