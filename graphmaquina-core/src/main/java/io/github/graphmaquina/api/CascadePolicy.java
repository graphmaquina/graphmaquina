package io.github.graphmaquina.api;

/**
 * How {@code cascadeAll} handles a peer whose cascade fails. FAIL_FAST stops at the first failure and lets it
 * propagate. COLLECT runs every peer, rolls back each one that ends in a transition abort, then throws one
 * {@code CascadeException} carrying those aborts; any other failure propagates at once. Either way the failure
 * poisons the transaction.
 */
public enum CascadePolicy { FAIL_FAST, COLLECT }
