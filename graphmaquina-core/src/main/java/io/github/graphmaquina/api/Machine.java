package io.github.graphmaquina.api;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Base of every generated facade: what it offers besides its operations.
 * There is no generic selector here on purpose; the operations declared on the interface are the only way in.
 */
public interface Machine<E, S extends Enum<S>, T extends Enum<T>> {

    GraphDefinition<E, S, T> definition();

    /** Evaluates the watch of the current state. Ignored if there is no watch or no rule holds. */
    Outcome<E, S, T> reevaluate(E entity);

    /** Structural inspection: the identifiers declared from {@code state}. Evaluates no guard. */
    Set<T> transitionsFrom(S state);

    /** Runs the whole call on the machine's executor, never on the calling thread. A failed future after close(). */
    <R> CompletableFuture<R> async(Supplier<R> call);
}
