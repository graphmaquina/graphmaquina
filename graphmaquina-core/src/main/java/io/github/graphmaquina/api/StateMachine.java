package io.github.graphmaquina.api;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Governs the state S of entity E. Does not protect an entity against concurrent transitions:
 * that is the caller's responsibility.
 * SPI: applications receive facades generated from their {@code @StateGraph} interfaces; this type is what the generated code wraps.
 */
public interface StateMachine<E, S extends Enum<S>> {

    MachineKey<E, S> key();

    GraphDefinition<E, S> definition();

    /** Domain-level results as a value. Only a technical failure throws. Blocks on an asynchronous edge. */
    Outcome<E, S> transitionTo(E entity, S target);

    /** Evaluates the watch of the current state. Ignored if there is no watch or no rule holds. */
    Outcome<E, S> reevaluate(E entity);

    Set<S> allowedFrom(S state);

    /** The same machine, running on its executor. Never runs anything on the calling thread. */
    AsyncStateMachine<E, S> async();

    /** Runs {@code call} on the machine's executor; implements {@link Machine#async}. Failed future after close(). */
    <T> CompletableFuture<T> supplyAsync(Supplier<T> call);

    /**
     * Same as the two-argument {@code transitionTo}, carrying a second, operation-specific value that guards and
     * actions read back through their context's {@code input} accessors. A {@code null} input delegates to that
     * overload; this default rejects any other value instead of silently dropping it.
     *
     * @throws NullPointerException if {@code entity} or {@code target} is null
     * @throws UnsupportedOperationException if {@code input} is not null and this machine does not support it
     */
    default Outcome<E, S> transitionTo(E entity, S target, Object input) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(target, "target");
        if (input == null) {
            return transitionTo(entity, target);
        }
        throw new UnsupportedOperationException("this StateMachine does not support operation input");
    }
}
