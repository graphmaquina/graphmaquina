package io.github.graphmaquina.api;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Base of every generated facade: what it offers besides its operations, their checks and its choices. The entry points
 * are the operations declared on the interface, the checks it declares for them and for its gates, the choices it
 * declares, each of which runs, or checks, the first admitted of the candidate identifiers it names, and what it
 * inherits from this interface. {@link #execute(Object, Enum)} and {@link #eligibility(Object, Enum)} find a declared
 * operation, or a declared check, by its identifier: they open no other entry point, so an identifier the facade
 * declares no operation without input for, or no check without input for, is refused, never run.
 */
public interface Machine<E, S extends Enum<S>, T extends Enum<T>> {

    GraphDefinition<E, S, T> definition();

    /**
     * Runs the operation without input that this facade declares for {@code transition}, as calling that operation
     * does, and returns its outcome.
     *
     * @throws NullPointerException if an argument is null, naming the parameter, before anything runs
     * @throws IllegalArgumentException if the facade declares no operation without input for {@code transition}, as
     *     for an identifier whose operation takes an input, one with no operation, a gate or an external edge, before
     *     anything runs
     */
    Outcome<E, S, T> execute(E entity, T transition);

    /**
     * Runs the check without input that this facade declares for {@code transition}, as calling that check does, and
     * returns its answer.
     *
     * @throws NullPointerException if an argument is null, naming the parameter, before anything runs
     * @throws IllegalArgumentException if the facade declares no check without input for {@code transition}, before
     *     anything runs
     */
    Eligibility<E, S, T> eligibility(E entity, T transition);

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
