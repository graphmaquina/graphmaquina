package io.github.graphmaquina.api;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Asynchronous counterpart of {@link StateMachine}, returning outcomes as futures; {@link StateMachine#async()}
 * submits each call to the machine's executor, which decides the thread it runs on.
 * SPI: the generated facades do not use it; their {@code async(...)} goes through {@link StateMachine#supplyAsync}.
 */
public interface AsyncStateMachine<E, S extends Enum<S>, T extends Enum<T>> {

    CompletableFuture<Outcome<E, S, T>> execute(E entity, T transition);

    CompletableFuture<Outcome<E, S, T>> reevaluate(E entity);

    /**
     * Same as the two-argument {@code execute}, carrying a second, operation-specific value that guards and
     * actions read back through their context's {@code input} accessors. A {@code null} input is refused on the
     * calling thread, as a {@code null} entity is; a call that carries no input is the two-argument {@code execute}.
     * This default fails the returned future instead of silently dropping any input.
     *
     * @throws NullPointerException if {@code entity}, {@code transition} or {@code input} is null
     */
    default CompletableFuture<Outcome<E, S, T>> execute(E entity, T transition, Object input) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(transition, "transition");
        Objects.requireNonNull(input, "input");
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("this AsyncStateMachine does not support operation input"));
    }
}
