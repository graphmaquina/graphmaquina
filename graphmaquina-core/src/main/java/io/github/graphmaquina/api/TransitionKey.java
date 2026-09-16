package io.github.graphmaquina.api;

import lombok.NonNull;

/**
 * Unique key of a transition inside a machine: its origin and its identifier. Two identifiers may connect the
 * same pair of states, and one identifier may leave several states; the origin is what disambiguates.
 *
 * @param <S> state enum
 * @param <T> transition identifier enum
 * @param from the state the transition leaves
 * @param transition the identifier of the transition
 */
public record TransitionKey<S extends Enum<S>, T extends Enum<T>>(@NonNull S from, @NonNull T transition) {

    @Override
    public String toString() {
        return from.name() + "/" + transition.name();
    }
}
