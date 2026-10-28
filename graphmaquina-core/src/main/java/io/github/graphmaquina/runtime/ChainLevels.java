package io.github.graphmaquina.runtime;

/**
 * Where a call stands on the path of continuations that leads to it from a call of the application, in the two chains
 * that run after commits on the calling thread. {@code watch} counts the watch reactions on that path, 0 for a call of
 * the application, and bounds them by maxWatchChain; {@code automatic} counts the automatic attempts on it, 0 for a
 * call of the application but an advance, whose own attempt is at 1, and bounds them by maxAutomaticChain. A watch
 * reaction is one watch level above the call whose commit triggered it and passes the automatic level on unchanged;
 * an attempt of a chain is one automatic level above the attempt before it, or above the call whose commit started the
 * chain, and passes the watch level on unchanged.
 *
 * @param watch the watch reactions on the path, the call itself included when it is one
 * @param automatic the automatic attempts on the path, the call itself included when it is one
 */
record ChainLevels(int watch, int automatic) { }
