package io.github.graphmaquina.api;

import lombok.NonNull;

/** Result of running a {@link TransitionGuard} or {@link AsyncTransitionGuard}: either {@link Pass} or {@link Reject}. */
public sealed interface GuardResult permits GuardResult.Pass, GuardResult.Reject {

    /** A guard that allows the transition to proceed. */
    record Pass() implements GuardResult { }

    /** A guard that blocks the transition, carrying the reason it was rejected. */
    record Reject(@NonNull String reason) implements GuardResult {
        public Reject {
            if (reason.isBlank()) {
                throw new IllegalArgumentException("reason must not be blank");
            }
        }
    }

    static GuardResult pass() {
        return new Pass();
    }

    static GuardResult reject(String reason) {
        return new Reject(reason);
    }
}
