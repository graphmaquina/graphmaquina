package io.github.graphmaquina.example.loan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The hand wiring of the dependency injection page: the set built once from its collaborators, its facade handed out,
 * and the set closed by its owner.
 */
class LoanWiringTest {

    @Test
    void theWiringHandsOutTheFacadeAndClosesTheSet() {
        Loan loan = new Loan("w1", Instant.parse("2026-12-01T00:00:00Z"));

        try (LoanWiring wiring = new LoanWiring(new FakeReservations())) {
            assertTrue(wiring.loans().returnBook(loan).isApplied());
        }

        assertEquals(LoanStatus.RETURNED, loan.status());
    }
}
