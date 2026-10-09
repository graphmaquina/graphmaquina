package io.github.graphmaquina.example.billing;

/**
 * Identifiers of the booking machine's transitions. Five are exits, which only the end of a payment runs:
 * {@code DEPOSIT_PAID}, {@code DEPOSIT_DECLINED}, {@code BALANCE_PAID}, {@code BALANCE_DECLINED} and
 * {@code PAYMENT_VOIDED}, the last one from both bound states. {@code NO_ROOM} is the route {@code DEPOSIT_PAID}
 * takes when no room can be held.
 */
public enum BookingTransition {
    BOOK, DEPOSIT_PAID, DEPOSIT_DECLINED, BALANCE_PAID, BALANCE_DECLINED, PAYMENT_VOIDED, NO_ROOM, CANCEL
}
