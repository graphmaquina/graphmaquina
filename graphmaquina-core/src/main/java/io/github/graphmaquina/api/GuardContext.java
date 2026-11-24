package io.github.graphmaquina.api;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * A guard decides, it does not mutate: no cascade, no depth, and a flow it can only read. Its peer views and its
 * {@link #flow()} work only on the thread that called {@code test()} and only until the root transaction ends:
 * elsewhere they throw {@code WrongThreadException}, afterwards {@code StaleContextException}. Once it ends, the
 * context, its peer views and its flow let go of the call: one retained after it keeps in memory nothing of the call
 * but the entity and the input of this step, which {@link #entity()} and {@link #input()} still answer, and, for a
 * flow, also the root call's entity, with that entity's identity when the call logs at DEBUG, so it keeps a value of
 * the flow only when that value is one of them. What the guard throws, or completes its stage with, is never routed,
 * whatever {@code failsOn} or a recovery rule of its edge declares: once unwrapped from CompletionException and
 * ExecutionException, it aborts the call with it as cause, unless it is a failure of this call, for which the engine
 * throws it as itself or, when the transaction is poisoned, the failure that poisoned the transaction. An Error
 * thrown as itself propagates.
 * {@link io.github.graphmaquina.error.TransitionAbortedException} says what aborting the call means inside a cascade.
 * A guard also runs for an eligibility check, which {@link StateMachine#eligibility} answers without the call: there
 * the check stands for the root transaction, so the context works until the check ends, and what would abort the call
 * fails the check with an {@link io.github.graphmaquina.error.EligibilityCheckException} instead.
 * A synchronous guard may itself start a check, through a facade's check method, a choice check,
 * {@link StateMachine#eligibility} or {@link StateMachine#chooseEligibility}, of an entity that no enclosing call or
 * check holds, as {@link io.github.graphmaquina.error.ReentrantTransitionException} says: it runs as any check, nested
 * in this guard's call or check, and what it throws reaches the guard, which fails as a guard that throws unless it
 * catches it. Anything else a guard starts, and a check from an asynchronous guard's {@code test}, is refused.
 */
public interface GuardContext<E, S extends Enum<S>, T extends Enum<T>> {

    E entity();

    S from();

    /** The identifier that selected the edge being evaluated. */
    T transition();

    S to();

    <P, Q extends Enum<Q>, U extends Enum<U>> PeerView<P, Q, U> peer(MachineKey<P, Q, U> key);

    <P, Q extends Enum<Q>, U extends Enum<U>, M extends Machine<P, Q, U>> PeerView<P, Q, U> peer(Class<M> peer);

    /**
     * The flow of this call, read-only: the values the steps of the call put before this guard runs, those of the
     * action that cascaded to this step included, the root call's entity, which {@link FlowView#entity()}
     * describes, and, when the root machine records its trail, what the call took before this guard's step, as
     * {@link FlowView#trail()} describes. The view has no {@code put} or {@code remove}, and no cast gives it one.
     * Like peer views, {@code flow()} and every read of the view it returns work only on the thread that called
     * {@code test()} and only until the root transaction ends: elsewhere they throw {@code WrongThreadException},
     * afterwards {@code StaleContextException}.
     */
    FlowView flow();

    /**
     * The instant of this request, on the clock the root machine was built with, {@code clock(InstantSource)} or
     * {@link java.time.InstantSource#system()}: read the first time a context of the request asks, and the same for
     * every later {@code now()} of the request, so every guard and action of one call, of the steps it cascades to and
     * of the checks its guards start, sees one instant, and an eligibility check, or a choice check for all its
     * candidates, sees one of its own. A request that never asks reads no clock. Anything but an Error that the clock
     * throws, and a null instant, refused with a NullPointerException, is thrown here, and the guard fails as one that
     * throws it. Like peer views, it works only on the thread that called {@code test()} and only until the root
     * transaction ends: elsewhere it throws {@code WrongThreadException}, afterwards {@code StaleContextException}.
     */
    Instant now();

    /**
     * The value {@code key} keeps in this request: computed by {@code supplier} the first time a guard of the request
     * asks for {@code key}, then the same value for every later guard of the request that asks, so evidence that
     * several guards or several candidates need is fetched once. A request is one call, an operation, an execute, a
     * choice or an attempt, with the steps it cascades to and the checks its guards start, or one check; a choice
     * check is one request for all its candidates. Keys are compared by reference, as {@link MemoKey} says. A supplier
     * that answers null aborts the call, or fails the check, as a guard that returns null does, and keeps nothing; one
     * that throws is the guard throwing, and keeps nothing either. Like peer views, it works only on the thread that
     * called {@code test()} and only until the root transaction ends: elsewhere it throws {@code WrongThreadException},
     * afterwards {@code StaleContextException}.
     *
     * @throws NullPointerException if {@code key} or {@code supplier} is null
     */
    <V> V memo(MemoKey<V> key, Supplier<? extends V> supplier);

    /**
     * The value passed as this operation's second argument, if any; empty when called without one, and always empty in
     * a cascaded step, the exit of a sub-machine, a reevaluation or an advance, which carry none. A {@code null} second
     * argument is refused before the call starts, so it never reads as empty. Scoped to this invocation only, never a
     * value left over from an earlier call. In an eligibility check, the input the check carries, as
     * {@code eligibility(entity, transition, input)} receives it; empty for a check that carries none.
     */
    default Optional<?> input() {
        return Optional.empty();
    }

    /**
     * {@link #input()} cast to {@code type}: the same reference, without copying or converting it.
     *
     * @throws NullPointerException if {@code type} is null
     * @throws IllegalArgumentException if {@code type} is a primitive class token; use its wrapper class instead
     * @throws ClassCastException if an input is present but not assignable to {@code type}
     */
    default <I> Optional<I> input(Class<I> type) {
        return InputSupport.cast(type, this::input);
    }

    /**
     * {@link #input()} when it is an instance of {@code type}, the same reference without copying or converting it;
     * empty when there is no input or it is of another type, so a guard shared by operations with inputs of different
     * types can read the one it knows.
     *
     * @throws NullPointerException if {@code type} is null
     * @throws IllegalArgumentException if {@code type} is a primitive class token; use its wrapper class instead
     */
    default <I> Optional<I> inputIfInstance(Class<I> type) {
        return InputSupport.castIfInstance(type, this::input);
    }
}
