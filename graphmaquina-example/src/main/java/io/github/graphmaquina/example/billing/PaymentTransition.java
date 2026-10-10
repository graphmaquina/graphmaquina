package io.github.graphmaquina.example.billing;

/** Identifiers of the payment machine's transitions, all three from {@code AUTHORIZING}. */
public enum PaymentTransition { CAPTURE, DECLINE, VOID }
