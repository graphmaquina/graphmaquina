package io.stategraph.api;

import lombok.NonNull;

/** A single rule of a watch: the condition to test and the state to move to when it holds. */
public record WatchRule<E, S extends Enum<S>, P, Q extends Enum<Q>>(@NonNull NamedCondition<E, P, Q> condition, @NonNull S target) {
}
