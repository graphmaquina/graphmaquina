package io.github.graphmaquina.example.loan;

/**
 * What the reservations service knows about the book of one loan.
 *
 * @param waitingReaders how many readers wait for the book
 * @param heldForCourse whether a course holds the book
 */
public record ReservationSummary(int waitingReaders, boolean heldForCourse) { }
