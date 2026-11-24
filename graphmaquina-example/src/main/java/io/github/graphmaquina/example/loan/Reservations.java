package io.github.graphmaquina.example.loan;

/** The library's reservations service, which the loan's guards ask before an extension is granted. */
public interface Reservations {

    /**
     * What the service knows about the book of the loan {@code loanId}.
     *
     * @throws ReservationsUnavailable if the service cannot answer
     */
    ReservationSummary summaryFor(String loanId);
}
