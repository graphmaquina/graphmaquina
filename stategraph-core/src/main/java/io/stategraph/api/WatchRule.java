package io.stategraph.api;

import java.util.Objects;

/** A single rule of a watch: the condition to test and the state to move to when it holds. */
public record WatchRule<E, S extends Enum<S>, P, Q extends Enum<Q>>(NamedCondition<E, P, Q> condition, S target) {

    public WatchRule {
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(target, "target");
    }
}
