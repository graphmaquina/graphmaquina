package io.github.graphmaquina.api;

import java.util.Optional;

/**
 * A guard decides, it does not mutate: no cascade and no depth. Its peer views work only on the thread that called
 * {@code test()} and only until the root transaction ends: elsewhere they throw {@code WrongThreadException},
 * afterwards {@code StaleContextException}. What the guard throws, or completes its stage with, is never routed,
 * whatever {@code failsOn} or a recovery rule of its edge declares: once unwrapped from CompletionException and
 * ExecutionException, it aborts the call with it as cause, a failure of this call that it lets through excepted, for
 * which the engine throws it as itself or, when the transaction is poisoned, the failure that poisoned the transaction.
 * An Error thrown as itself propagates.
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
     * The value passed as this operation's second argument, if any; empty when called with one argument or an
     * explicit {@code null}, and always empty in a cascaded step or a reevaluation, which carry none. Scoped to this
     * invocation only, never a value left over from an earlier call.
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
}
