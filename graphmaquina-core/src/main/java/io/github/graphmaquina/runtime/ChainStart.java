package io.github.graphmaquina.runtime;

/**
 * Where an automatic chain may begin for one entity of a commit: the state the commit left it in, whether or not that
 * differs from the one it started in, and whether the last thing the commit did to it was a failure route that stops
 * the chain.
 *
 * @param ref the entity
 * @param state the state the entity's last log entry leaves it in
 * @param stopsChain true when that last entry is the hop of a failure route that does not continue the chain; false
 *     when it is any other hop or the start of a sub-machine
 */
record ChainStart(EntityRef ref, Enum<?> state, boolean stopsChain) { }
