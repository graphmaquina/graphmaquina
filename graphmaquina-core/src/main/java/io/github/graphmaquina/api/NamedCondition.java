package io.github.graphmaquina.api;

import lombok.NonNull;

/** A {@link WatchCondition} paired with the name used to identify it in diagnostics and messages. */
public record NamedCondition<E, P, Q extends Enum<Q>, U extends Enum<U>>(
        @NonNull String name, @NonNull WatchCondition<E, P, Q, U> condition) {

    public NamedCondition {
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }
}
