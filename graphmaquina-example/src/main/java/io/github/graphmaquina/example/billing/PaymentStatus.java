package io.github.graphmaquina.example.billing;

/** States of a Payment: authorized first, then captured, declined or voided, all three terminal. */
public enum PaymentStatus { AUTHORIZING, CAPTURED, DECLINED, VOIDED }
