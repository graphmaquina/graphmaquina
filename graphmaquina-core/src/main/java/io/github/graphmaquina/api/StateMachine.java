package io.github.graphmaquina.api;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Governs the state S of entity E through the transitions T it declares. Does not protect an entity against
 * concurrent transitions: that is the caller's responsibility.
 * SPI: applications receive facades generated from their declarations; this type is what the generated code wraps.
 */
public interface StateMachine<E, S extends Enum<S>, T extends Enum<T>> {

    MachineKey<E, S, T> key();

    GraphDefinition<E, S, T> definition();

    /** Domain-level results as a value. Only a technical failure throws. Blocks on an asynchronous edge. */
    Outcome<E, S, T> execute(E entity, T transition);

    /** Evaluates the watch of the current state. Ignored if there is no watch or no rule holds. */
    Outcome<E, S, T> reevaluate(E entity);

    /** Structural inspection: the identifiers declared from {@code state}. Evaluates no guard. */
    Set<T> transitionsFrom(S state);

    /** The same machine, running on its executor. Never runs anything on the calling thread. */
    AsyncStateMachine<E, S, T> async();

    /** Runs {@code call} on the machine's executor; implements {@link Machine#async}. Failed future after close(). */
    <R> CompletableFuture<R> supplyAsync(Supplier<R> call);

    /**
     * Same as the two-argument {@code execute}, carrying a second, operation-specific value that guards and
     * actions read back through their context's {@code input} accessors. A {@code null} input delegates to that
     * overload; this default rejects any other value instead of silently dropping it.
     *
     * @throws NullPointerException if {@code entity} or {@code transition} is null
     * @throws UnsupportedOperationException if {@code input} is not null and this machine does not support it
     */
    default Outcome<E, S, T> execute(E entity, T transition, Object input) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(transition, "transition");
        if (input == null) {
            return execute(entity, transition);
        }
        throw new UnsupportedOperationException("this StateMachine does not support operation input");
    }
}
