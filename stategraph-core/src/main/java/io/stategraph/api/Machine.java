package io.stategraph.api;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Base of every {@code @StateGraph} interface: what the generated facade offers besides its operations.
 * There is no {@code transitionTo} here on purpose; the operations declared on the interface are the only way in.
 */
public interface Machine<E, S extends Enum<S>> {

    GraphDefinition<E, S> definition();

    /** Evaluates the watch of the current state. Ignored if there is no watch or no rule holds. */
    Outcome<E, S> reevaluate(E entity);

    Set<S> allowedFrom(S state);

    /** Runs the whole call on the machine's executor, never on the calling thread. A failed future after close(). */
    <T> CompletableFuture<T> async(Supplier<T> call);
}
