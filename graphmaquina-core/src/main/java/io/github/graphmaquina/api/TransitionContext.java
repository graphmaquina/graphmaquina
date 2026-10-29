package io.github.graphmaquina.api;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Context handed to a {@link TransitionAction} or an {@link AsyncTransitionAction}: the edge, the depth, peer views,
 * cascades and the call's flow. A cascade runs the peer's step in this transaction and returns Applied or Failed; once
 * its arguments are accepted, whatever else it ends in poisons the transaction, so the root cannot commit even if the
 * action catches it: the action receives a failure of the call as it is, anything else as an abort of the call that
 * carries it, and an Error as itself. {@code cascadeAll} accepts its arguments before its first element runs, so this
 * holds between two of its elements too. A VirtualMachineError, a StackOverflowError or an OutOfMemoryError for one,
 * that strikes once the arguments are accepted reaches the action at once, before anything records it, since recording
 * it could need the stack or the memory that ran out; once the action caught it and goes on, the engine records it, on
 * the stack of the action's step, as an abort of that step that carries no cause, unless the transaction was already
 * poisoned: as soon as the action reads a peer or cascades again, which then throws
 * {@code PoisonedUnitOfWorkException}, and at the latest when the action ends, unless an Error it throws as itself ends
 * it, which propagates. One that strikes before, while the cascade checks whether it may run, copies or checks its
 * arguments, or looks a facade type up, poisons nothing, as a refused argument does: the cascade has not started.
 * {@code cascadeAll} copies its collection before any element runs and never reads it again: a change made to the
 * collection while the cascade runs, by an element's action for instance, changes neither which elements run nor their
 * order. Peer views, cascades and the call's {@link #flow()} work only on the thread that called {@code process()} and
 * only until the root transaction ends: elsewhere they throw {@code WrongThreadException}, afterwards
 * {@code StaleContextException}. Once it ends, the context, its peer views and its flow let go of the call: one
 * retained after it keeps in memory nothing of the call but the entity and the input of this step, which
 * {@link #entity()} and {@link #input()} still answer, and, for a flow, also the root call's entity, with that
 * entity's identity when the call logs at DEBUG, so it keeps a value of the flow only when that value is one of them.
 * Cascades must also run before the commit begins, and not while a step is rolled back: a retained context used to
 * cascade from a commit getter or setter, or from an identity's {@code equals} or {@code hashCode} that a rollback
 * calls, throws {@code IllegalStateException} before it stages anything, since the commit has fixed its changes and
 * the rollback is undoing them.
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
 * original failure's reason, never from that throwable's string, whether or not the transaction is poisoned. That abort
 * carries the throwable as cause and holds as suppressed the throwable that selected the failure route, when a
 * throwable selected it and is not the same one. These aborts that the engine builds for the step of a recovery action
 * hold that throwable as suppressed too: for a null outcome or stage or a wait that timed out, was interrupted or was
 * refused, where on a transaction already poisoned the call ends in the failure that poisoned it, as it is; for a
 * sub-machine that the recovery hop enters and that cannot start; and for an exit that a terminal state selects after
 * the recovery and that cannot run. {@link io.github.graphmaquina.error.TransitionAbortedException} says what aborting
 * the call means inside a cascade.
 */
public interface TransitionContext<E, S extends Enum<S>, T extends Enum<T>> {

    E entity();

    S from();

    /** The identifier that selected the edge being executed. */
    T transition();

    S to();

    /**
     * 0 at the root transition; n at the n-th level of cascade. The exit of a sub-machine runs one level below the step
     * that ended the child, unless the child stood in a terminal state while its parent was still running the step that
     * entered the bound state: that exit runs once that step is over, one level below it.
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
     * when this step fails with a failure its edge expects, one it returns or a throwable of a type the edge declares,
     * whether a route takes it or the step aborts with {@code UnhandledFailureException}, and when this step is a
     * {@code COLLECT} element whose abort the cascade collects, the call drops every write made since this step took
     * its hop, this action's and those of the steps it cascaded to, and keeps what earlier steps wrote; the recovery
     * action of a route writes again. An identity's {@code equals} or {@code hashCode} that throws while a
     * {@code COLLECT} element is rolled back stops that rollback, which leaves what it had not undone. Once a failed
     * cascade poisoned the call, a failure this step's edge expects rolls nothing back: this step ends in the failure
     * that poisoned the call and keeps its writes, unless a {@code COLLECT} collects that failure, which drops the
     * writes of the whole element this step ran in. No other abort undoes a write. The caller receives the flow as the
     * call ended, frozen, from {@link Outcome#flow()} or from the {@code flow()} of the failure the call throws. A
     * cascaded step and the exit of a sub-machine receive no {@link #input()}: copy what they need from the input into
     * the flow first. Like peer views and cascades, {@code flow()} and every operation of the flow it returns work only
     * on the thread that called {@code process()} and only until the root transaction ends: elsewhere they throw
     * {@code WrongThreadException}, afterwards {@code StaleContextException}. Cascades additionally require that the
     * commit has not begun and that no step is being rolled back. An asynchronous action writes before it returns its
     * stage, never in a callback of the stage, which may run on another thread.
     */
    FlowContext flow();

    /**
     * The value passed as this operation's second argument, if any; empty when called without one, and always empty in
     * a cascaded step, the exit of a sub-machine, a reevaluation or an advance, which carry none. A {@code null} second
     * argument is refused before the call starts, so it never reads as empty. Scoped to this invocation only, never a
     * value left over from an earlier call.
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
