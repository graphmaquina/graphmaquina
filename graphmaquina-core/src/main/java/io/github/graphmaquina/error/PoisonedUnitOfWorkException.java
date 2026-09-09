package io.github.graphmaquina.error;

import java.util.Objects;
import lombok.Getter;

/** Use of a UnitOfWork already poisoned by an earlier failure in the same transaction. */
@Getter
public final class PoisonedUnitOfWorkException extends TransitionAbortedException {

    private final StateMachineException original;

    public PoisonedUnitOfWorkException(AbortDetails details, StateMachineException original) {
        super(details, "the transaction already failed earlier: " + Objects.requireNonNull(original, "original").getMessage(), original);
        this.original = original;
    }
}
