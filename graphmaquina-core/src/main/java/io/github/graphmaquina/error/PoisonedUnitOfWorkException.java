package io.github.graphmaquina.error;

import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.MachineCall;
import java.util.Objects;
import lombok.Getter;

/**
 * A context's {@code peer(...)}, {@code cascade(...)} or {@code cascadeAll(...)} called in a transaction that an
 * earlier failure already poisoned; {@code original()} is that failure. Only those calls check the poison: a
 * {@code PeerView} obtained before it still reads.
 *
 * <p>A cascade, single or one element of {@code cascadeAll}, poisons its transaction when it fails after its arguments
 * were accepted. Whatever leaves it then poisons: an engine exception, such as an unknown machine key, an ambiguous
 * identity, a cycle, the depth limit or an abort inside the peer's step; a throwable from a callback the engine does
 * not wrap, such as an identity function; or an Error. A failure of the call itself poisons as it is; anything else
 * poisons through an abort of the caller's step that names the cascade and carries it as cause, and the action receives
 * that abort, or the Error itself. A VirtualMachineError, a StackOverflowError or an OutOfMemoryError for one, reaches
 * the action before anything records it; an action that catches it and goes on poisons the transaction with an abort of
 * its own step that carries no cause, recorded at the latest when the action ends, unless an Error it throws as itself
 * ends it, and that abort is {@code original()} when the action then reads a peer or cascades. A cascade that names an
 * unknown machine, by key or by facade type, poisons too, and {@code cascadeAll} refuses it that way even over an empty
 * collection. COLLECT handles one case differently: it rolls back an element that ends in a transition abort of the
 * call, unless a nested cascade of that element poisoned the transaction with a failure that is no failure of the call,
 * and poisons once, with the aggregate, after the last element. Nothing else poisons. Refusing a cascade's arguments
 * happens before anything is staged, and a peer read stages nothing. A null argument or element, or an exception or an
 * Error the collection throws while {@code cascadeAll} copies it, comes before the machine is resolved, so even with an
 * unknown machine it poisons nothing. An identifier from another machine's catalogue is refused, without poisoning, as
 * soon as that catalogue is known: before the lookup for a key, which carries it, and once the facade has resolved for
 * a facade type, so an unknown facade is refused first, and poisons.
 *
 * <p>A poisoned transaction never commits. Once the engine has the outcome of the action that caught the failure, it
 * throws the failure that poisoned the transaction instead of applying that outcome. It throws that failure too when
 * the action throws or completes its stage with a failure of the call, this exception included, or, unless it is a
 * recovery action, with a throwable that a type its edge declares accepts, in its failsOn or in one of its recovery
 * rules; no declared type accepts an Error, a CancellationException or a StateMachineException. Unless that action is a
 * recovery action, whose failure route was already taken, no failure route is taken, so no recovery action runs. The
 * engine's own aborts of the action step, for a null outcome or stage or for a wait that timed out, was interrupted
 * or was refused, also end in the failure that poisoned the transaction. The action can still end the call in another
 * failure, such as an abort that carries a throwable no declared type accepts, or an Error it throws as itself. For
 * synchronous throws and exceptional completions of completed or pending stages alike, the engine checks the poison
 * before it reads the message of a throwable that a declared type accepts and that an action other than a recovery
 * action throws or completes with; the JDK may already have read that message, for example when {@code supplyAsync}
 * wrapped the throwable in a CompletionException. When the edge's action, other than a recovery action, throws or
 * completes with a throwable that no declared type accepts, a CancellationException included, and that is neither an
 * Error thrown as itself nor a failure of this call, the engine aborts the call with a message composed from that
 * throwable's string, which may read its message, whether or not the transaction is poisoned. A throwable that a
 * recovery action throws or completes with is never routed, whatever the recovery edge declares: an Error thrown as
 * itself propagates; the engine throws a failure of this call as itself or, when the transaction is poisoned, the
 * failure that poisoned the transaction; and on any other throwable the engine aborts the call with a message
 * composed from the recovery action's name and the original failure's reason, never from that throwable's string,
 * whether or not the transaction is poisoned. That abort carries the throwable as cause and holds as suppressed the
 * throwable that selected the failure route, when a throwable selected it and is not the same one.
 */
@Getter
public final class PoisonedUnitOfWorkException extends TransitionAbortedException {

    private final StateMachineException original;

    public PoisonedUnitOfWorkException(MachineCall call, AbortDetails details, StateMachineException original) {
        super(call, CallPhase.ACTION, details, "the transaction already failed earlier: "
                + Objects.requireNonNull(original, "original").getMessage(), original);
        this.original = original;
    }
}
