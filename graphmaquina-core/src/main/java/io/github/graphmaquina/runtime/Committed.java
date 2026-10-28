package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.Outcome;
import java.util.Set;

/**
 * What a root call hands back to the engine code that ran it, once its post-commit ran: its outcome, what its commit
 * amounts to per entity, {@link CommitSummary#EMPTY} when it committed nothing, and the entities that a call nested
 * in its post-commit moved, which nothing records yet, so the set is always empty.
 *
 * @param outcome the outcome the call returns
 * @param summary the summary of the call's commit, or {@link CommitSummary#EMPTY}
 * @param movedBelow the entities a call nested in the post-commit moved; always empty
 */
record Committed<E, S extends Enum<S>, T extends Enum<T>>(Outcome<E, S, T> outcome, CommitSummary summary,
                                                          Set<EntityRef> movedBelow) { }
