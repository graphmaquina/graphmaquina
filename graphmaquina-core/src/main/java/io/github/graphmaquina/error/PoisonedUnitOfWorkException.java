package io.github.graphmaquina.error;

import java.util.Objects;
import lombok.Getter;

/**
 * A peer read or a cascade attempted in a transaction that an earlier failure already poisoned; {@code original()}
 * is that failure.
 *
 * <p>A cascade, single or one element of {@code cascadeAll}, poisons its transaction when it fails after its
 * arguments were accepted. Whatever leaves it then poisons: an engine exception, such as an unknown machine key, an
 * ambiguous identity, a cycle, the depth limit or an abort inside the peer's step; an exception from a callback the
 * engine does not wrap, such as an identity function; or an Error. A cascade that names an unknown facade type
 * poisons too. COLLECT handles one case differently: it rolls back an element that ends in a transition abort, and
 * poisons once, with the aggregate, after the last element. Nothing else poisons. Refusing a cascade's arguments, a
 * null argument or element or an identifier from another machine's catalogue, happens before anything is staged, and
 * a peer read stages nothing.
 *
 * <p>A poisoned transaction never commits. When the action that caught the failure returns, returns a failure or
 * throws an exception its failsOn lists, the poison is thrown instead: no failure route is taken, so no recovery
 * action runs.
 */
@Getter
public final class PoisonedUnitOfWorkException extends TransitionAbortedException {

    private final StateMachineException original;

    public PoisonedUnitOfWorkException(AbortDetails details, StateMachineException original) {
        super(details, "the transaction already failed earlier: " + Objects.requireNonNull(original, "original").getMessage(), original);
        this.original = original;
    }
}
