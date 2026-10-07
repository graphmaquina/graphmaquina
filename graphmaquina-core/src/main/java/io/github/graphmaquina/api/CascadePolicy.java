package io.github.graphmaquina.api;

/**
 * How {@code cascadeAll} handles a peer whose cascade fails. FAIL_FAST stops at the first failure. COLLECT runs
 * every peer, rolls back each one that ends in a transition abort of the call, then throws one
 * {@code CascadeException} carrying those aborts; it stops at once on any other failure, and on an element whose
 * step a nested cascade poisoned with a failure that is no failure of the call, such as an unknown machine or a
 * peer's null state, even when the element's action caught it. When a {@code cascadeAll} whose arguments were
 * accepted fails, the transaction is poisoned, and the action receives a failure of the call as it is, anything else
 * as an abort that carries it, and an Error as itself.
 */
public enum CascadePolicy { FAIL_FAST, COLLECT }
