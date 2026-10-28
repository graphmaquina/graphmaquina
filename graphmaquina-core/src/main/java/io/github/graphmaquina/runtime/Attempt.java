package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.Outcome;
import java.util.Set;

/**
 * What one automatic attempt hands back to the chain, or the advance, that made it. When the attempt committed, the
 * last entry its commit logged for its entity says whether that entry is a failure route that stops the chain, and
 * the state it leaves the entity in, which the chain goes on from: a sub-machine exit that the attempt's own step ran
 * may have moved the entity past the destination of the edge it ran. An attempt that committed nothing stops no chain,
 * and its state is the one it read.
 *
 * @param outcome the outcome of the attempt
 * @param movedBelow the entities a call nested in the attempt's post-commit moved, as {@link Committed} says
 * @param stopsChain true when the commit's last entry for the entity is a failure route that does not continue
 * @param state the state the commit's last entry for the entity leaves it in, or the state the attempt read
 */
record Attempt<E, S extends Enum<S>, T extends Enum<T>>(Outcome<E, S, T> outcome, Set<EntityRef> movedBelow,
                                                        boolean stopsChain, S state) { }
