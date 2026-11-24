package io.github.graphmaquina.error;

import java.util.Objects;
import java.util.Optional;

/**
 * Root of the exceptions the library defines, though not of everything it throws: an invalid argument is a
 * {@code NullPointerException} or an {@code IllegalArgumentException}, a builder misused or left incomplete an
 * {@code IllegalStateException}, and a context used off its thread a {@code WrongThreadException}. Not meant to be
 * serialized: it carries live domain objects.
 */
public class StateMachineException extends RuntimeException {

    public StateMachineException(String message) {
        super(Objects.requireNonNull(message, "message"));
    }

    public StateMachineException(String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message"), Objects.requireNonNull(cause, "cause"));
    }

    /**
     * What a guard itself threw, or completed its stage with, once unwrapped from CompletionException and
     * ExecutionException, when that is what this exception reports: a {@link TransitionAbortedException} the engine
     * raised for it, at any depth, and an {@link EligibilityCheckException} of a check whose guard did. Empty for every
     * other exception, among them a guard that answered null or a null stage and a wait for a guard's stage that timed
     * out, was interrupted or was refused, which the engine raises itself. An adapter that must let a guard's own
     * exception reach its caller rethrows it from here; a guard can also declare the types that do so on their own, as
     * {@code GraphGuard.propagates} says.
     */
    public Optional<Throwable> guardFailure() {
        return Optional.empty();
    }

    /**
     * The {@link UninitializedStateException} this exception reports: this exception when it is one, or else its cause
     * when that is one, as the abort of a call whose root or cascaded peer has a null state, and the failure of a check
     * of an entity with a null state, carry it. Empty otherwise. One accessor recognises a null state whichever of
     * them reports it.
     */
    public Optional<UninitializedStateException> uninitializedState() {
        if (this instanceof UninitializedStateException uninitialized) {
            return Optional.of(uninitialized);
        }
        return getCause() instanceof UninitializedStateException uninitialized
                ? Optional.of(uninitialized) : Optional.empty();
    }
}
