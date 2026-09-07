package io.stategraph.error;

import java.util.Objects;

/** Use of a UnitOfWork already poisoned by an earlier failure in the same transaction. */
public final class PoisonedUnitOfWorkException extends TransitionAbortedException {

    private final StateMachineException original;

    public PoisonedUnitOfWorkException(AbortDetails details, StateMachineException original) {
        super(details, "the transaction already failed earlier: " + Objects.requireNonNull(original, "original").getMessage(), original);
        this.original = original;
    }

    public StateMachineException original() { return original; }
}
