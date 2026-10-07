package io.github.graphmaquina.api;

/**
 * How {@code cascadeAll} handles a peer whose cascade fails. FAIL_FAST stops at the first failure. COLLECT runs every
 * peer, rolls back each one that ends in a transition abort of the call, then throws one {@code CascadeException}
 * carrying those aborts; any other failure stops it at once, and so does an abort that carries a failure that is no
 * failure of the call. Either way, the action receives what {@code cascadeAll} hands it, a failure of the call as it
 * is, anything else as an abort that carries it and an Error as itself, and the failure poisons the transaction.
 */
public enum CascadePolicy { FAIL_FAST, COLLECT }
