package io.github.graphmaquina.api;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Base of every generated facade: what it offers besides its operations and their checks.
 * There is no generic selector here on purpose; the entry points are the operations declared on the interface, the
 * checks it declares for them and what it inherits from this interface.
 */
public interface Machine<E, S extends Enum<S>, T extends Enum<T>> {

    GraphDefinition<E, S, T> definition();

    /** Evaluates the watch of the current state. Ignored if there is no watch or no rule holds. */
    Outcome<E, S, T> reevaluate(E entity);

    /**
     * Makes one automatic attempt, as {@link StateMachine#advance} says: runs the first automatic edge of the entity's
     * state that is admitted, trying them in their declared order, or returns {@link Outcome.Ignored}, never
     * {@link Outcome.Rejected}, with {@link IgnoreReason#NO_AUTOMATIC_TRANSITION} or
     * {@link IgnoreReason#NO_CANDIDATE_ADMITTED}. A failure throws as it would in an operation. The attempts that
     * follow once it committed are in {@link Outcome#automaticSteps()}.
     */
    Outcome<E, S, T> advance(E entity);

    /**
     * Structural inspection: the identifiers declared from {@code state}. Evaluates no guard; a check the facade
     * declares asks whether one entity would be admitted now for the operation it checks, its guards included.
     */
    Set<T> transitionsFrom(S state);

    /**
     * Runs {@code call} on the machine's executor through {@link StateMachine#supplyAsync}: a new virtual thread by
     * default, wherever a supplied executor runs it otherwise, the calling thread included, or a new thread when that
     * executor is ForkJoinPool.commonPool() and its parallelism is below two. A call the executor refuses, as the
     * default one does after close(), returns a failed future.
     */
    <R> CompletableFuture<R> async(Supplier<R> call);
}
