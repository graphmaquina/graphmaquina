package io.stategraph.api;

import java.util.Objects;

/** Result of running a {@link TransitionAction} or {@link AsyncTransitionAction}: either {@link Ok} or {@link Failed}. */
public sealed interface ActionOutcome permits ActionOutcome.Ok, ActionOutcome.Failed {

    /** Successful outcome of an action. */
    record Ok() implements ActionOutcome { }

    /** Failed outcome of an action, carrying the reason it failed. */
    record Failed(String reason) implements ActionOutcome {
        public Failed {
            Objects.requireNonNull(reason, "reason");
            if (reason.isBlank()) {
                throw new IllegalArgumentException("reason must not be blank");
            }
        }
    }

    static ActionOutcome ok() {
        return new Ok();
    }

    static ActionOutcome failed(String reason) {
        return new Failed(reason);
    }
}
