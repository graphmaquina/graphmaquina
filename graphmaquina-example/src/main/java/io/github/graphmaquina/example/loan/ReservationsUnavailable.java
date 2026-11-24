package io.github.graphmaquina.example.loan;

/** The reservations service could not answer; the guards that ask it propagate this failure to the caller. */
public final class ReservationsUnavailable extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ReservationsUnavailable(String message) {
        super(message);
    }
}
