package io.github.graphmaquina.api;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Context handed to a {@link TransitionAction} or an {@link AsyncTransitionAction}: the edge, the depth, peer views,
 * cascades and the call's flow. A cascade runs the peer's step in this transaction and returns Applied or Failed; once
 * its arguments are accepted, whatever else it ends in poisons the transaction, so the root cannot commit even if the
 * action catches it: the action receives a failure of the call as it is, anything else as an abort of the call that
 * carries it, and an Error as itself. {@code cascadeAll} copies its collection before any element runs and never
 * reads it again: a change made to the collection while the cascade runs, by an element's action for instance,
 * changes neither which elements run nor their order. Peer views, cascades and the call's {@link #flow()} work only
 * on the thread that called {@code process()} and only until the root transaction ends: elsewhere they throw
 * {@code WrongThreadException}, afterwards {@code StaleContextException}.
 * Cascades must also run before the commit begins: a retained context used to cascade from a commit getter or
 * setter throws {@code IllegalStateException} before it stages anything, since the commit has fixed its changes.
 * What the action throws, or completes its stage with, once unwrapped from CompletionException and ExecutionException,
 * is its own failure. A throwable of the edge's action, other than a recovery action, that a type its edge declares
 * accepts, in {@link TransitionSpec#failsOn()} or in one of its {@link TransitionSpec#recoveryRules()}, takes the route
 * of the most specific such type, for the types the DSL and RecoveryRule admit (the
 * {@link TransitionSpec#recoveryRules()} parameter says what an interface type in a hand-built failsOn does): that
 * rule's recovery, with the rule's failure kind, or onFailure for a failsOn type. A CancellationException, a
 * StateMachineException and an Error never match: no type the edge declares accepts them. On any other throwable that
 * is neither an Error nor a failure of this call, the engine aborts the call with it as cause; an Error thrown as
 * itself propagates, and on an Error that completes the stage, or arrives wrapped in one of those two classes, the
 * engine aborts the call too. A declared type also accepts the JDK exceptions this context throws, such as a
 * NullPointerException for a null argument, an IllegalArgumentException for an identifier of another catalogue, a
 * ClassCastException from {@link #input(Class)} or a WrongThreadException off its thread: they are matched like any
 * other throwable of the action. A failure of this call that the action throws or completes its stage with is never
 * routed: the engine throws it as itself or, when the transaction is poisoned, the failure that poisoned the
 * transaction. Once a failed cascade poisoned the transaction, a throwable of an action other than a recovery action
 * that a declared type accepts takes no route either: the engine throws the failure that poisoned the transaction. For
 * a throwable of an action other than a recovery action that a declared type accepts, the engine checks the poison
 * before it reads the throwable's message, for synchronous throws and exceptional completions of completed or pending
 * stages alike; the JDK may already have read that message, for example when {@code supplyAsync} wrapped the throwable
 * in a CompletionException. When the edge's action, other than a recovery action, throws or completes with a throwable
 * that no declared type accepts, a CancellationException included, and that is neither an Error thrown as itself nor a
 * failure of this call, the engine aborts the call with a message composed from that throwable's string, which may read
 * its message, whether or not the transaction is poisoned. A throwable that a recovery action throws or completes with
 * is never routed, whatever the recovery edge declares: an Error thrown as itself propagates; the engine throws a
 * failure of this call as itself or, when the transaction is poisoned, the failure that poisoned the transaction; and
 * on any other throwable the engine aborts the call with a message composed from the recovery action's name and the
 * original failure's reason, never from that throwable's string, whether or not the transaction is poisoned.
 * {@link io.github.graphmaquina.error.TransitionAbortedException} says what aborting the call means inside a cascade.
 */
public interface TransitionContext<E, S extends Enum<S>, T extends Enum<T>> {

    E entity();

    S from();

    /** The identifier that selected the edge being executed. */
    T transition();

    S to();

    /**
     * 0 at the root transition; n at the n-th level of cascade, where the exit of a sub-machine is one level deeper
     * than the step that ended it.
     */
    int depth();

    <P, Q extends Enum<Q>, U extends Enum<U>> PeerView<P, Q, U> peer(MachineKey<P, Q, U> key);

    <P, Q extends Enum<Q>, U extends Enum<U>> Outcome<P, Q, U> cascade(MachineKey<P, Q, U> key, P peer, U transition);

    <P, Q extends Enum<Q>, U extends Enum<U>> List<Outcome<P, Q, U>> cascadeAll(MachineKey<P, Q, U> key,
                                                                                Collection<? extends P> peers,
                                                                                U transition, CascadePolicy policy);

    /**
     * The peer machine identified by its facade type; the generics of {@code M} fix {@code P}, {@code Q} and
     * {@code U}.
     */
    <P, Q extends Enum<Q>, U extends Enum<U>, M extends Machine<P, Q, U>> PeerView<P, Q, U> peer(Class<M> peer);

    <P, Q extends Enum<Q>, U extends Enum<U>, M extends Machine<P, Q, U>> Outcome<P, Q, U> cascade(
            Class<M> peer, P entity, U transition);

    <P, Q extends Enum<Q>, U extends Enum<U>, M extends Machine<P, Q, U>> List<Outcome<P, Q, U>> cascadeAll(
            Class<M> peer, Collection<? extends P> peers, U transition, CascadePolicy policy);

    /**
     * The flow of this call: typed values that every step of the call shares, the root call's entity, which
     * {@link FlowView#entity()} describes, and, when the root machine records its trail, what the call took so far,
     * this step's hop included, as {@link FlowView#trail()} describes. A write is undone with the step that made it:
     * when this step fails with a failure its edge expects, one it returns or a throwable of a type the edge
     * declares, whether a route takes it or the step aborts with {@code UnhandledFailureException}, and when this
     * step is an element of a {@code COLLECT} cascade and aborts, the call drops every write made since this step
     * took its hop, this action's and those of the steps it cascaded to, and keeps what earlier steps wrote; the
     * recovery action of a route writes again. No other abort undoes a write. The caller receives the flow as the
     * call ended, frozen, from {@link Outcome#flow()} or from the {@code flow()} of the failure the call throws. A
     * cascaded step and the exit of a sub-machine receive no {@link #input()}: copy what they need from the input into
     * the flow first. Like peer views and cascades, {@code flow()} and every operation of the flow it returns work only
     * on the thread that called {@code process()} and only until the root transaction ends: elsewhere they throw
     * {@code WrongThreadException}, afterwards {@code StaleContextException}. Cascades additionally require that
     * the commit has not begun. An asynchronous action writes before it
     * returns its stage, never in a callback of the stage, which may run on another thread.
     */
    FlowContext flow();

    /**
     * The value passed as this operation's second argument, if any; empty when called with one argument or an
     * explicit {@code null}, and always empty in a cascaded step, the exit of a sub-machine or a reevaluation, which
     * carry none. Scoped to this invocation only, never a value left over from an earlier call.
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
