package io.github.graphmaquina.api;

import java.util.List;
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
     * continue, the entity's machine is {@link AutomaticChain#ON_ADVANCE}, whose entities chain only after an advance
     * of their own, or a call made on this thread inside its post-commit, a hook's call, a watch reaction or an earlier
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
     * Error, never throw to the caller. On an {@link AutomaticChain#ON_ADVANCE} machine, this is the only call after
     * which the machine takes the automatic edges on its own, though a call that names an automatic edge's identifier
     * still runs that edge and chains nothing: its own entity's chain runs over every automatic edge it meets, while
     * the other entities of that machine its commits moved do not chain, and a call made on this thread inside its
     * post-commit that moves its entity stops that chain; only an advance of the entity chains it again, the caller's
     * next one or a nested one, such as a hook's call to advance.
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
     * check made where it may not start are refused before the check starts and throw as themselves. A check may not
     * start where no call may, such as from an action, an observer or the test call of an asynchronous guard, with one
     * exception: a synchronous guard may check an entity that no enclosing call or check holds, as
     * {@code ReentrantTransitionException} says. To tell, it runs the entity's identity function, and the equals of the
     * identity it returns, before it starts: what either throws, and the {@code NullPointerException} of a function
     * that returns null, propagate as they are. Such a check runs as any check, nested in the guard's call or check; in
     * its scope, its own step, from the read of its entity's identity to its last guard, and everything that step runs,
     * a read of a held entity through a context's peer view or a binding lookup and a cascade through a kept action
     * context of the enclosing call throw, and its failure throws into the guard. While a check runs, no call may start
     * on its thread, and a check may start only from a synchronous guard of that check or of a check nested in it. A
     * check that started and cannot reach an answer throws an {@code EligibilityCheckException}, never a
     * {@code CallFailedException}; an Error propagates as itself. A check logs lines of its own on the runtime logger,
     * at DEBUG and TRACE, which start with {@code check=<id>} in place of {@code call=<id>}.
     *
     * @throws NullPointerException if {@code entity} or {@code transition} is null
     * @throws IllegalArgumentException if {@code transition} belongs to another catalogue, which only raw types pass
     * @throws io.github.graphmaquina.error.ReentrantTransitionException if the check is made where it may not start
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
     * @throws io.github.graphmaquina.error.ReentrantTransitionException if the check is made where it may not start
     * @throws io.github.graphmaquina.error.EligibilityCheckException if the check cannot reach an answer
     * @throws UnsupportedOperationException if this machine does not support eligibility checks
     */
    default Eligibility<E, S, T> eligibility(@NonNull E entity, @NonNull T transition, @NonNull Object input) {
        throw new UnsupportedOperationException("this StateMachine does not support eligibility checks");
    }

    /**
     * Runs, as one call, the first of {@code candidates} that is admitted for {@code entity} in the state it is in now,
     * trying them in the order given. Each candidate gets the admission of an execute of its identifier: the binding
     * check of a sub-machine, the edge lookup, which refuses a candidate that no edge keys from the state as
     * {@link RejectionKind#NO_TRANSITION} with the reason of the state's policy, the refusal of a reserved exit, then
     * its guards in declaration order, with the context they get in that execute. A refused candidate passes to the
     * next one, and the first one admitted runs within the same call as execute runs an edge, without running its
     * guards again.
     * <ul>
     *   <li>It returns {@link Outcome.Applied} or {@link Outcome.Failed}, whose {@code requestedTransition()} is the
     *       candidate that ran.</li>
     *   <li>It returns {@link Outcome.Ignored} with {@link IgnoreReason#NO_CANDIDATE_ADMITTED} when every candidate was
     *       refused; its {@code rejections()} lists each refusal in the order tried, which its {@code explanation()}
     *       names. It writes nothing, and the guards of those candidates ran.</li>
     *   <li>It never returns {@link Outcome.Rejected}.</li>
     * </ul>
     * A failure throws as it would in execute: always a {@code CallFailedException} of this call, such as the
     * {@code UnhandledFailureException} of a domain failure that no route takes, or an Error; a guard that throws fails
     * the call, and the candidates after it are not tried. Blocks on an asynchronous edge. Once the call committed, it
     * runs the automatic chain of each entity it moved as execute does: a choice is no advance, so an entity of an
     * {@link AutomaticChain#ON_ADVANCE} machine does not chain. The call's {@link MachineCall#request()} is
     * {@link CallRequest#CHOOSE}, which requests no identifier. A null argument or candidate, an empty list, a
     * candidate listed twice, a candidate of another catalogue and a call made where no call may start are refused
     * before the call starts and throw as themselves; such a call logs nothing. This default supports no choice: it
     * throws instead of running; the machines the runtime builds implement it.
     *
     * @throws NullPointerException if {@code entity}, {@code candidates} or one of its elements is null
     * @throws IllegalArgumentException if {@code candidates} is empty, lists an identifier twice or holds one of
     *     another catalogue, which only raw types pass
     * @throws io.github.graphmaquina.error.ReentrantTransitionException if the call is made where no call may start
     * @throws UnsupportedOperationException if this machine does not support choices
     */
    default Outcome<E, S, T> choose(@NonNull E entity, @NonNull List<T> candidates) {
        throw new UnsupportedOperationException("this StateMachine does not support choices");
    }

    /**
     * Same as the two-argument {@code choose}, carrying a second, operation-specific value that the guards of each
     * candidate tried, and the actions of the one that runs, read back through their context's {@code input}
     * accessors, as they do in {@code execute(entity, candidate, input)}. A {@code null} input is refused before the
     * call starts, as a {@code null} entity is; a choice that carries no input is the two-argument {@code choose}. This
     * default supports no choice: it throws instead of running; the machines the runtime builds implement it.
     *
     * @throws NullPointerException if {@code entity}, {@code candidates}, one of its elements or {@code input} is null
     * @throws IllegalArgumentException if {@code candidates} is empty, lists an identifier twice or holds one of
     *     another catalogue, which only raw types pass
     * @throws io.github.graphmaquina.error.ReentrantTransitionException if the call is made where no call may start
     * @throws UnsupportedOperationException if this machine does not support choices
     */
    default Outcome<E, S, T> choose(@NonNull E entity, @NonNull List<T> candidates, @NonNull Object input) {
        throw new UnsupportedOperationException("this StateMachine does not support choices");
    }

    /**
     * Answers which of {@code candidates} {@code choose(entity, candidates)} would run for {@code entity} in the state
     * it is in now, as a {@link ChoiceEligibility}. It checks each candidate in the order given, as
     * {@code eligibility(entity, candidate)} checks it, and answers {@link ChoiceEligibility.Chosen} at the first one
     * that is {@link Eligibility.Eligible}, with the {@link Eligibility.Ineligible} answers of the candidates before
     * it, or {@link ChoiceEligibility.NoneAdmitted} with the answer of every candidate. Each candidate's check is a
     * check of its own, as eligibility says: it reads the state again, under a deadline of its own, logs its own lines
     * and, whatever it answers or throws, writes no state and runs no action, hook, watch reaction or automatic
     * attempt. A check that throws ends the answer, and the candidates after it are not checked. Like a check, it is
     * no call and tells no observer. A null argument or candidate, an empty list, a candidate listed twice, a candidate
     * of another catalogue and a check made where it may not start, as {@code eligibility} says, are refused before the
     * first check starts and throw as themselves; such an answer logs nothing. From a synchronous guard, each
     * candidate's check is nested in the guard's call or check, and what the entity's identity function or its
     * identity's equals throws before it starts propagates as it is, as {@code eligibility} says. This default supports
     * no choice check: it throws instead of answering; the machines the runtime builds implement it.
     *
     * @throws NullPointerException if {@code entity}, {@code candidates} or one of its elements is null
     * @throws IllegalArgumentException if {@code candidates} is empty, lists an identifier twice or holds one of
     *     another catalogue, which only raw types pass
     * @throws io.github.graphmaquina.error.ReentrantTransitionException if the check is made where it may not start
     * @throws io.github.graphmaquina.error.EligibilityCheckException if the check of a candidate cannot reach an answer
     * @throws UnsupportedOperationException if this machine does not support choice checks
     */
    default ChoiceEligibility<E, S, T> chooseEligibility(@NonNull E entity, @NonNull List<T> candidates) {
        throw new UnsupportedOperationException("this StateMachine does not support choice checks");
    }

    /**
     * Same as the two-argument {@code chooseEligibility}, asking about the choice that carries {@code input}: which
     * candidate {@code choose(entity, candidates, input)} would run. The guards read {@code input} back through their
     * context's {@code input} accessors, as they do in that choice. A {@code null} input is refused, as a {@code null}
     * entity is; a check that carries no input is the two-argument {@code chooseEligibility}. This default supports no
     * choice check: it throws instead of answering; the machines the runtime builds implement it.
     *
     * @throws NullPointerException if {@code entity}, {@code candidates}, one of its elements or {@code input} is null
     * @throws IllegalArgumentException if {@code candidates} is empty, lists an identifier twice or holds one of
     *     another catalogue, which only raw types pass
     * @throws io.github.graphmaquina.error.ReentrantTransitionException if the check is made where it may not start
     * @throws io.github.graphmaquina.error.EligibilityCheckException if the check of a candidate cannot reach an answer
     * @throws UnsupportedOperationException if this machine does not support choice checks
     */
    default ChoiceEligibility<E, S, T> chooseEligibility(@NonNull E entity, @NonNull List<T> candidates,
                                                         @NonNull Object input) {
        throw new UnsupportedOperationException("this StateMachine does not support choice checks");
    }
}
