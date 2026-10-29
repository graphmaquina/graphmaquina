package io.github.graphmaquina.api;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import lombok.NonNull;

/**
 * Governs the state S of entity E through the transitions T it declares. Does not protect an entity against
 * concurrent transitions: that is the caller's responsibility. Two calls on one entity from different threads are
 * not serialized, and the check that runs before phase 2 writes catches only the interleavings that leave the entity
 * in another state than the call started from.
 * A call's asynchronous waits, those of the peers it cascades to and of the exits of sub-machines it runs included,
 * are bounded by the asyncTimeout of this machine, never by a peer's or a parent's own. When this machine declares no
 * asyncTimeout, the waits of its cascaded peers, and of the exits their steps run, are unbounded, whatever the own
 * asyncTimeout of those peers and parents: only an edge that declares a timeout of its own still bounds its action's
 * wait. Within a registry, a machine must declare one when an edge that its calls can run implicitly is asynchronous:
 * the exit of a binding in which it is the child, a failure route of such an edge, an exit it leads to, or an exit
 * selected by a terminal initial descendant of a binding its own edge enters.
 * The watch reactions and the automatic attempts a call triggers are not part of it: each runs as a new root, bounded
 * by the asyncTimeout of the machine that reacts or attempts, counted from the moment that root starts. A machine
 * built standalone runs in a registry of its own that is internal and unreachable, so nothing can close it, and its
 * default executor is never closed.
 * SPI: applications receive facades generated from their declarations; this type is what the generated code wraps.
 */
public interface StateMachine<E, S extends Enum<S>, T extends Enum<T>> {

    MachineKey<E, S, T> key();

    GraphDefinition<E, S, T> definition();

    /**
     * Returns the domain-level result as a value; a technical failure throws, always a {@code CallFailedException} of
     * this call or an Error. Two domain-level results throw as well: a domain failure that no recovery rule of its edge
     * takes, on an edge without onFailure, throws {@code UnhandledFailureException}, and a rejection inside a cascade
     * aborts the whole call. A call is rejected as {@link RejectionKind#DETACHED} when this machine is bound as a
     * sub-machine, a binding finds the entity's parent, and no parent holds the entity: none is in the bound state, or
     * leaving it, with a childOf that answers this entity; and as {@link RejectionKind#RESERVED_EXIT} when the
     * identifier is an exit of the sub-machine bound to the current state. When the step ends a sub-machine that a
     * parent held when the step began, the parent's exit runs in the same call unless that parent is in flight, or the
     * step moved it out of the bound state or entered it again, and the outcome is still this step's; an exit that is
     * rejected or fails without a route aborts the call. A null argument, an identifier of another catalogue and a call
     * made where no call may start are refused before the call starts and throw as themselves. Blocks on an
     * asynchronous edge. Once the call committed and its hooks and watch reactions ran, it runs the automatic chain of
     * each entity it moved, unless the last hop it committed for that entity is a failure route that does not
     * continue, or a call made on this thread inside its post-commit, a hook's call, a watch reaction or an earlier
     * attempt, moved the entity too, which decided its chain; {@link Outcome#automaticSteps()} lists those attempts,
     * and a failure of one of them, anything but an Error, never throws to the caller: it is listed there, and
     * {@link Outcome#automaticFailure()}, {@link Outcome#automaticAbort()} and {@link Outcome#automaticBlock()} find
     * it, nested ones included. A call that a hook starts on another thread, through {@link #async()}, even one the
     * hook waits for, is not made on this thread: this call still chains the entity from the state its own commit left
     * it in, its attempt reads the live state, and the chain can go on past a failure route that stopped the other
     * call's chain, so a hook makes its calls synchronously, on its own thread.
     */
    Outcome<E, S, T> execute(E entity, T transition);

    /**
     * Evaluates the watch of the current state. Ignored if there is no watch or no rule holds. A failure throws,
     * always a {@code CallFailedException} of this call or an Error; a null argument and a call made where no call
     * may start are refused before the call starts and throw as themselves.
     */
    Outcome<E, S, T> reevaluate(E entity);

    /**
     * Makes one automatic attempt on the entity's current state. Its candidates are the automatic edges of that
     * state, in the order {@code definition().automaticFrom(state)} lists them. Each one gets the admission of an
     * execute of its identifier: the binding check of a sub-machine, then its guards in declaration order, with the
     * context they get in that execute, whose input is empty. A refused candidate passes to the next one, and the
     * first one admitted runs within the same call as execute runs an edge, without running its guards again.
     * <ul>
     *   <li>It never returns {@link Outcome.Rejected}: it requests no identifier.</li>
     *   <li>It returns {@link Outcome.Ignored} with {@link IgnoreReason#NO_AUTOMATIC_TRANSITION} when the state has no
     *       automatic edge, terminal states included; then of the callbacks the machine declares, only the state
     *       getter ran: no identity function, lookup, guard or action.</li>
     *   <li>It returns {@link Outcome.Ignored} with {@link IgnoreReason#NO_CANDIDATE_ADMITTED} when every candidate was
     *       refused, by a guard or as {@link RejectionKind#DETACHED}; its {@code rejections()} lists each refusal in
     *       the order tried, which its {@code explanation()} names. The guards of those candidates ran.</li>
     *   <li>Neither ignored outcome writes anything.</li>
     * </ul>
     * A failure throws as it would in execute: always a {@code CallFailedException} of this call, such as the
     * {@code UnhandledFailureException} of a domain failure that no route takes or, for a {@link GraphDefinition}
     * written by hand whose automaticFrom throws, a {@code TransitionAbortedException} in RESOLVE; or an Error. A null
     * argument and a call made where no call may start are refused before the call starts and throw as themselves.
     * Once the attempt committed, it runs the automatic chain of each entity it moved, as execute does, its own
     * entity's included unless the attempt's failure took a route that does not continue: the attempts that follow
     * are each a call of their own, which {@link Outcome#automaticSteps()} lists, and their failures, anything but an
     * Error, never throw to the caller.
     */
    Outcome<E, S, T> advance(E entity);

    /**
     * Structural inspection: the identifiers declared from {@code state}. Evaluates no guard; {@link #eligibility}
     * asks whether one entity would be admitted now, its guards included.
     */
    Set<T> transitionsFrom(S state);

    /**
     * The same machine, submitting each call to its executor: a new virtual thread by default, wherever a supplied
     * executor runs it otherwise, or a new thread when that executor is ForkJoinPool.commonPool() and its parallelism
     * is below two, as {@code StateMachines.Builder.asyncExecutor} says. A null argument throws on the calling thread,
     * and a submission the executor refuses returns a failed future.
     */
    AsyncStateMachine<E, S, T> async();

    /**
     * Runs {@code call} on the machine's executor; implements {@link Machine#async}. A submission the executor refuses,
     * as the default one does after close(), returns a failed future. The call completes the returned future as it
     * would complete the one CompletableFuture.supplyAsync returns, but it is queued as a plain task, which
     * CompletableFuture's get and join never run on the thread that waits, and anything it throws, an Error included,
     * fails the future, wrapped in a CompletionException unless it is one, so that nothing escapes on the executor's
     * thread; an InterruptedException it throws interrupts the thread that ran it again. That holds even for a
     * throwable whose printing throws, which leaves the future of supplyAsync pending on JDK 21 and fails it with an
     * empty message on JDK 25: the message of the CompletionException is then {@code <toString threw <class name>>} in
     * place of the throwable's string.
     */
    <R> CompletableFuture<R> supplyAsync(Supplier<R> call);

    /**
     * Same as the two-argument {@code execute}, carrying a second, operation-specific value that guards and
     * actions read back through their context's {@code input} accessors. A {@code null} input is refused before the
     * call starts, as a {@code null} entity is; a call that carries no input is the two-argument {@code execute}.
     * This default rejects every input instead of silently dropping it.
     *
     * @throws NullPointerException if {@code entity}, {@code transition} or {@code input} is null
     * @throws UnsupportedOperationException if this machine does not support operation input
     */
    default Outcome<E, S, T> execute(@NonNull E entity, @NonNull T transition, @NonNull Object input) {
        throw new UnsupportedOperationException("this StateMachine does not support operation input");
    }

    /**
     * Answers whether {@code execute(entity, transition)} would be admitted for {@code entity} in the state it is in
     * now, as an {@link Eligibility}: {@link Eligibility.Eligible}, or {@link Eligibility.Ineligible} with the reason,
     * the kind and the guard of the refusal. This default supports no eligibility check: it throws instead of
     * answering. The machines the runtime builds implement it with the admission of execute's own step, and nothing
     * after it: the binding check of a sub-machine, the edge lookup, the refusal of a reserved exit, then the guards
     * in declaration order until one rejects, each with the context it gets in execute; asynchronous guards are awaited
     * under this machine's asyncTimeout, counted from the start of the check. A check is no call: whatever it answers
     * or throws, it writes no state, runs no action, hook, watch reaction or automatic attempt, starts no sub-machine,
     * runs no exit and tells no observer, and a context its guards got is stale once it ends. It answers for any
     * identifier of the catalogue, as execute does. A {@code null} argument, an identifier of another catalogue and a
     * check made where no call may start, such as from a guard, an action or an observer, are refused before the check
     * starts and throw as themselves; while a check runs, no call or check may start on its thread either. A check that
     * cannot reach an answer throws an {@code EligibilityCheckException}, never a {@code CallFailedException}; an Error
     * propagates as itself. A check logs lines of its own on the runtime logger, at DEBUG and TRACE, which start with
     * {@code check=<id>} in place of {@code call=<id>}.
     *
     * @throws NullPointerException if {@code entity} or {@code transition} is null
     * @throws IllegalArgumentException if {@code transition} belongs to another catalogue, which only raw types pass
     * @throws io.github.graphmaquina.error.ReentrantTransitionException if the check is made where no call may start
     * @throws io.github.graphmaquina.error.EligibilityCheckException if the check cannot reach an answer
     * @throws UnsupportedOperationException if this machine does not support eligibility checks
     */
    default Eligibility<E, S, T> eligibility(@NonNull E entity, @NonNull T transition) {
        throw new UnsupportedOperationException("this StateMachine does not support eligibility checks");
    }

    /**
     * Same as the two-argument {@code eligibility}, asking about the operation that carries {@code input}: whether
     * {@code execute(entity, transition, input)} would be admitted. The guards read {@code input} back through their
     * context's {@code input} accessors, as they do in that execute. A {@code null} input is refused, as a
     * {@code null} entity is; a check that carries no input is the two-argument {@code eligibility}. This default
     * supports no eligibility check: it throws instead of answering; the machines the runtime builds implement it.
     *
     * @throws NullPointerException if {@code entity}, {@code transition} or {@code input} is null
     * @throws IllegalArgumentException if {@code transition} belongs to another catalogue, which only raw types pass
     * @throws io.github.graphmaquina.error.ReentrantTransitionException if the check is made where no call may start
     * @throws io.github.graphmaquina.error.EligibilityCheckException if the check cannot reach an answer
     * @throws UnsupportedOperationException if this machine does not support eligibility checks
     */
    default Eligibility<E, S, T> eligibility(@NonNull E entity, @NonNull T transition, @NonNull Object input) {
        throw new UnsupportedOperationException("this StateMachine does not support eligibility checks");
    }
}
