package io.stategraph.api;

import java.util.Objects;

/** A {@link WatchCondition} paired with the name used to identify it in diagnostics and messages. */
public record NamedCondition<E, P, Q extends Enum<Q>>(String name, WatchCondition<E, P, Q> condition) {

    public NamedCondition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(condition, "condition");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }
}
