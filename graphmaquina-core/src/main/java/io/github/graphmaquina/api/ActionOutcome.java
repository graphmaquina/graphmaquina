package io.github.graphmaquina.api;

import io.github.graphmaquina.internal.FailureTypes;
import java.util.Optional;
import lombok.NonNull;

/** Result of running a {@link TransitionAction} or {@link AsyncTransitionAction}: either {@link Ok} or {@link Failed}. */
public sealed interface ActionOutcome permits ActionOutcome.Ok, ActionOutcome.Failed {

    /** Successful outcome of an action. */
    record Ok() implements ActionOutcome { }

    /**
     * Failed outcome of an action, carrying the reason it failed, which must not be blank, and the failure kind the
     * action named, if any. A present kind must be a valid {@link FailureKind}: a named final class or record that
     * implements it and is not an interface, an enum, a local or anonymous class, or a Throwable. The constructor
     * that takes only the reason names no kind.
     *
     * @param reason why the action failed
     * @param failureKind the failure kind the action named, or empty when it named none
     */
    record Failed(@NonNull String reason, @NonNull Optional<Class<? extends FailureKind>> failureKind)
            implements ActionOutcome {
        public Failed {
            if (reason.isBlank()) {
                throw new IllegalArgumentException("reason must not be blank");
            }
            failureKind.ifPresent(FailureTypes::requireValidKind);
        }

        public Failed(String reason) {
            this(reason, Optional.empty());
        }
    }

    static ActionOutcome ok() {
        return new Ok();
    }

    static ActionOutcome failed(String reason) {
        return new Failed(reason);
    }

    /**
     * A failed outcome that names its failure kind.
     *
     * @param kind the failure kind of this failure
     * @param reason why the action failed, not blank
     * @return a {@link Failed} carrying {@code reason} and {@code kind}
     * @throws NullPointerException if {@code kind} or {@code reason} is null
     * @throws IllegalArgumentException if {@code kind} is not a valid failure kind or {@code reason} is blank
     */
    static ActionOutcome failed(Class<? extends FailureKind> kind, String reason) {
        return new Failed(reason, Optional.of(FailureTypes.requireValidKind(kind)));
    }
}
