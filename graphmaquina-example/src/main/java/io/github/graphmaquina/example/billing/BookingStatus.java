package io.github.graphmaquina.example.billing;

/** States of a Booking: requested, paying its deposit, paying its balance, then confirmed or cancelled. */
public enum BookingStatus { REQUESTED, DEPOSIT, BALANCE, CONFIRMED, CANCELLED }
