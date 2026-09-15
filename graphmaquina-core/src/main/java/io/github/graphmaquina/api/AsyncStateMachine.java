package io.github.graphmaquina.api;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Asynchronous counterpart of {@link StateMachine}, returning outcomes as futures instead of blocking.
 * SPI: applications receive facades generated from their {@code @StateGraph} interfaces; this type is what the generated code wraps.
 */
public interface AsyncStateMachine<E, S extends Enum<S>> {

    CompletableFuture<Outcome<E, S>> transitionTo(E entity, S target);

    CompletableFuture<Outcome<E, S>> reevaluate(E entity);

    /**
     * Same as the two-argument {@code transitionTo}, carrying a second, operation-specific value that guards and
     * actions read back through their context's {@code input} accessors. A {@code null} input delegates to that
     * overload; this default fails the returned future instead of silently dropping any other value.
     *
     * @throws NullPointerException if {@code entity} or {@code target} is null
     */
    default CompletableFuture<Outcome<E, S>> transitionTo(E entity, S target, Object input) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(target, "target");
        if (input == null) {
            return transitionTo(entity, target);
        }
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("this AsyncStateMachine does not support operation input"));
    }
}
