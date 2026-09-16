package io.github.graphmaquina.example.order;

/**
 * Identifiers of the order machine's transitions. {@code PAYMENT_DECLINED} names the recovery edge a declined
 * charge takes, and deliberately shares its spelling with the state it reaches: states and identifiers are two
 * distinct enums, so javac keeps {@code OrderStatus.PAYMENT_DECLINED} and this constant apart.
 */
public enum OrderTransition { PAY, RETRY, SHIP, CANCEL, PAYMENT_DECLINED }
