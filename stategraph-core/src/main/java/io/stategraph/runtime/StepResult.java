package io.stategraph.runtime;

import java.util.Optional;

/** Internal outcome of a step. The root turns it into an Outcome after the commit. */
sealed interface StepResult<S extends Enum<S>>
        permits StepResult.Moved, StepResult.Ignored, StepResult.Rejected, StepResult.FailedTo {

    S from();

    S to();

    /** ACTION or MOVE applied, pending commit. */
    record Moved<S extends Enum<S>>(S from, S to) implements StepResult<S> { }

    /** STAY, a convergent no-op, or a reevaluation with no matching rule. */
    record Ignored<S extends Enum<S>>(S from, S to) implements StepResult<S> { }

    /** FORBID or a guard that rejected. Nothing ran. */
    record Rejected<S extends Enum<S>>(S from, S to, String reason, Optional<String> guard) implements StepResult<S> { }

    /** Domain-level failure that took the onFailure route to movedTo. */
    record FailedTo<S extends Enum<S>>(S from, S to, S movedTo, String reason, Optional<Throwable> cause) implements StepResult<S> { }
}
