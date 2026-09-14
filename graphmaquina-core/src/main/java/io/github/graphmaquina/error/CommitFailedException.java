package io.github.graphmaquina.error;

import java.util.List;
import java.util.Objects;
import lombok.Getter;

/** Phase 2 failed to write. If applied() is not empty, the system was left inconsistent. */
@Getter
public final class CommitFailedException extends StateMachineException {

    /** Writes that were made and not undone. */
    private final List<Object> applied;
    /** Remaining planned commit work, including the failing setter when one threw. */
    private final List<Object> pending;

    public CommitFailedException(List<?> applied, List<?> pending, String message) {
        super(describe(applied, pending, message));
        this.applied = List.copyOf(applied);
        this.pending = List.copyOf(pending);
    }

    public CommitFailedException(List<?> applied, List<?> pending, Throwable cause) {
        super(describe(applied, pending, "a setter threw " + Objects.requireNonNull(cause, "cause")), cause);
        this.applied = List.copyOf(applied);
        this.pending = List.copyOf(pending);
    }

    private static String describe(List<?> applied, List<?> pending, String message) {
        Objects.requireNonNull(applied, "applied");
        Objects.requireNonNull(pending, "pending");
        Objects.requireNonNull(message, "message");
        return "phase 2 failed: " + message + "; applied " + applied.size() + ", pending " + pending.size();
    }
}
