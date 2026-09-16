package io.github.graphmaquina.api;

import lombok.NonNull;

/** A single rule of a watch: the condition to test and the transition of the owner to execute when it holds. */
public record WatchRule<E, S extends Enum<S>, T extends Enum<T>, P, Q extends Enum<Q>, U extends Enum<U>>(
        @NonNull NamedCondition<E, P, Q, U> condition, @NonNull T transition) {
}
