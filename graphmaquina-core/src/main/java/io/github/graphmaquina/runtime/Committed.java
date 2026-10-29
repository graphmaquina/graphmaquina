package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.Outcome;
import java.util.List;

/**
 * What a root call hands back to the engine code that ran it, once its post-commit ran: its outcome, what its commit
 * amounts to per entity, {@link CommitSummary#EMPTY} when it committed nothing, and the entities that the commits of
 * calls nested in its post-commit reported, empty when it committed nothing.
 *
 * @param outcome the outcome the call returns
 * @param summary the summary of the call's commit, or {@link CommitSummary#EMPTY}
 * @param movedBelow the entities the commits nested in the post-commit reported, as
 *     {@link PostCommitScope#moved()} lists them
 */
record Committed<E, S extends Enum<S>, T extends Enum<T>>(Outcome<E, S, T> outcome, CommitSummary summary,
                                                          List<EntityRef> movedBelow) { }
