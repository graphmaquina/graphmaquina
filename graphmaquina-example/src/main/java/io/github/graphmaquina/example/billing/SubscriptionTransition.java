package io.github.graphmaquina.example.billing;

/** Identifiers of the subscription machine's transitions. {@code RENEWED} and {@code LAPSE} are exits. */
public enum SubscriptionTransition { START_RENEWAL, RENEWED, LAPSE }
