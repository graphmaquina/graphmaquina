package io.stategraph.api;

import lombok.NonNull;

/** Result of running a {@link TransitionAction} or {@link AsyncTransitionAction}: either {@link Ok} or {@link Failed}. */
public sealed interface ActionOutcome permits ActionOutcome.Ok, ActionOutcome.Failed {

    /** Successful outcome of an action. */
    record Ok() implements ActionOutcome { }

    /** Failed outcome of an action, carrying the reason it failed. */
    record Failed(@NonNull String reason) implements ActionOutcome {
        public Failed {
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
