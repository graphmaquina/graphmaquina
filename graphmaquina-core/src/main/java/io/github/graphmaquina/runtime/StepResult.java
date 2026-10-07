package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.FailureKind;
import io.github.graphmaquina.api.IgnoreReason;
import io.github.graphmaquina.api.RejectionKind;
import java.util.Optional;

/**
 * Internal outcome of a step. The root turns it into an Outcome once its commit, if any, its hooks and its reactions
 * are done; a cascade turns it into one at once.
 */
sealed interface StepResult<S extends Enum<S>, T extends Enum<T>>
        permits StepResult.Applied, StepResult.Ignored, StepResult.Rejected, StepResult.Failed {

    S from();

    /** The selected edge ran and is pending commit. */
    record Applied<S extends Enum<S>, T extends Enum<T>>(S from, T transition, S to) implements StepResult<S, T> { }

    /** A reevaluation that selected no edge. No step builds it: reevaluate returns Outcome.Ignored without one. */
    record Ignored<S extends Enum<S>, T extends Enum<T>>(S from, IgnoreReason reason) implements StepResult<S, T> { }

    /**
     * No edge with that identifier leaves {@code from}, or a guard rejected: nothing was logged and no action ran,
     * though the guards up to the rejecting one did. Only a root step returns it; a deeper one aborts instead.
     */
    record Rejected<S extends Enum<S>, T extends Enum<T>>(S from, T transition, String reason, RejectionKind kind,
                                                          Optional<String> guard) implements StepResult<S, T> { }

    /**
     * Domain-level failure that took the recovery edge {@code failureTransition} to {@code movedTo}, with the failure
     * kind the outcome reports.
     */
    record Failed<S extends Enum<S>, T extends Enum<T>>(S from, T transition, S to, T failureTransition, S movedTo,
                                                        String reason, Optional<Throwable> cause,
                                                        Optional<Class<? extends FailureKind>> failureKind)
            implements StepResult<S, T> { }
}
