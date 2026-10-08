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
 * that abort, or the Error itself. A cascade that names an unknown machine, by key or by facade type, poisons too, and
 * {@code cascadeAll} refuses it that way even over an empty collection. COLLECT handles one case differently: it rolls
 * back an element that ends in a transition abort of the call, unless a nested cascade of that element poisoned the
 * transaction with a failure that is no failure of the call, and poisons once, with the aggregate, after the last
 * element. Nothing else poisons. Refusing a cascade's arguments happens before anything is staged, and a peer read
 * stages nothing. A null argument or element, or an exception or an Error the collection throws while
 * {@code cascadeAll} copies it, comes before the machine is resolved, so even with an unknown machine it poisons
 * nothing. An identifier from another machine's catalogue is refused, without poisoning, as soon as that catalogue is
 * known: before the lookup for a key, which carries it, and once the facade has resolved for a facade type, so an
 * unknown facade is refused first, and poisons.
 *
 * <p>A poisoned transaction never commits. When the action that caught the failure returns an outcome, or completes its
 * stage with one, throws or completes its stage with a failure of the call, this exception included, or, unless it is a
 * recovery action, throws a throwable that a type its edge declares accepts, in its failsOn or in one of its recovery
 * rules, the engine throws the failure that poisoned the transaction, the one {@code original()} reports, and never
 * this exception: an abort of the call, the abort that carries an Error or any other throwable, or COLLECT's aggregate;
 * an abort the engine raises itself, for a null outcome or stage or for a wait that timed out, was interrupted or was
 * refused, is thrown instead. Unless that action is a recovery action, whose failure route was already taken, no
 * failure route is taken, so no recovery action runs. For synchronous throws and exceptional completions of completed
 * or pending stages alike, the engine makes this check before it reads the message of a throwable that a declared type
 * accepts and that an action other than a recovery action throws or completes with; the JDK may already have read that
 * message, for example when {@code supplyAsync} wrapped the throwable in a CompletionException. When the edge's action,
 * other than a recovery action, throws or completes with a throwable that no declared type accepts and that is neither
 * an Error thrown as itself nor a failure of this call, the engine aborts the call with a message composed from that
 * throwable's string, which may read its message, whether or not the transaction is poisoned. A throwable that a
 * recovery action throws or completes with is never routed, whatever the recovery edge declares: an Error thrown as
 * itself propagates; the engine throws a failure of this call as itself or, when the transaction is poisoned, the
 * failure that poisoned the transaction; and on any other throwable the engine aborts the call with a message composed
 * from the recovery action's name and the original failure's reason, never from that throwable's string, whether or not
 * the transaction is poisoned.
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
