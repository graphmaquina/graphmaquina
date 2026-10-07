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
 * <p>A poisoned transaction never commits. When the action that caught the failure returns, returns a failure, throws a
 * throwable its failsOn lists, or lets a failure of the call through, this exception included, the engine throws the
 * failure that poisoned the transaction, the one {@code original()} reports, and never this exception: an abort of the
 * call, the abort that carries an Error or any other throwable, or COLLECT's aggregate. No failure route is taken, so
 * no recovery action runs.
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
