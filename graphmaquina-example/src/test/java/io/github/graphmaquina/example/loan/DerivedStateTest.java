package io.github.graphmaquina.example.loan;

import static io.github.graphmaquina.example.loan.LoanStatus.BORROWED;
import static io.github.graphmaquina.example.loan.LoanStatus.LOST;
import static io.github.graphmaquina.example.loan.LoanStatus.OVERDUE;
import static io.github.graphmaquina.example.loan.LoanStatus.RETURNED;
import static io.github.graphmaquina.example.loan.LoanTransition.MARK_OVERDUE;
import static io.github.graphmaquina.example.loan.LoanTransition.REPORT_LOST;
import static io.github.graphmaquina.example.loan.LoanTransition.RETURN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.runtime.StateMachines;
import org.junit.jupiter.api.Test;

/**
 * A state the entity does not store, which the persistence page shows: the entity keeps flags, its getter derives the
 * state from them, and its setter writes the flag the new state means, so the machine reads and writes it like a
 * stored one.
 */
class DerivedStateTest {

    /** A copy on loan that stores flags instead of a state. */
    static final class Copy {

        private boolean overdue;
        private boolean returned;
        private boolean lost;

        LoanStatus status() {
            if (lost) {
                return LOST;
            }
            if (returned) {
                return RETURNED;
            }
            return overdue ? OVERDUE : BORROWED;
        }

        void setStatus(LoanStatus status) {
            overdue = status == OVERDUE;
            returned = status == RETURNED;
            lost = status == LOST;
        }
    }

    private static GraphDefinition<Copy, LoanStatus, LoanTransition> graph() {
        return StateGraph.define(MachineKey.of(Copy.class, LoanStatus.class, LoanTransition.class))
                .initial(BORROWED).terminal(RETURNED).terminal(LOST)
                .statePolicy(BORROWED, Loans.BORROWED_POLICY)
                .statePolicy(OVERDUE, Loans.OVERDUE_POLICY)
                .transition(MARK_OVERDUE, BORROWED, OVERDUE).endTransition()
                .transition(RETURN, BORROWED, RETURNED).endTransition()
                .transition(RETURN, OVERDUE, RETURNED).endTransition()
                .transition(REPORT_LOST, BORROWED, LOST).external().endTransition()
                .transition(REPORT_LOST, OVERDUE, LOST).external().endTransition()
                .build();
    }

    @Test
    void theMachineReadsAndWritesTheDerivedState() {
        StateMachine<Copy, LoanStatus, LoanTransition> copies = StateMachines.from(graph())
                .state(Copy::status, Copy::setStatus)
                .build();
        Copy copy = new Copy();

        assertTrue(copies.execute(copy, MARK_OVERDUE).isApplied());
        assertEquals(OVERDUE, copy.status());
        assertTrue(copies.execute(copy, RETURN).isApplied());

        assertEquals(RETURNED, copy.status());
        assertTrue(copy.returned && !copy.overdue);
    }
}
