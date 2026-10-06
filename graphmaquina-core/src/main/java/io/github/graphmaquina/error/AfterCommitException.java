package io.github.graphmaquina.error;

import io.github.graphmaquina.api.MachineKey;
import java.util.Objects;
import lombok.Getter;

/**
 * A post-commit step threw an exception, checked ones included: an after-commit hook, or the registry listener; an
 * Error propagates unchanged instead. Nothing is rolled back; the caller's transaction decides. A hook runs only for
 * committed hops, so it always fails after phase 2 had written the states in memory, and the hooks after it, the
 * watch reactions and the listener do not run. The listener is called with the outcome of each execute or
 * reevaluate that reaches it, after that call's hooks and watch reactions; a call that throws first, such as one
 * whose step aborts, whose commit fails, whose hook throws or whose reaction raises an Error, does not call it. When
 * the listener fails after an Applied or Failed outcome, the states were written as well; after a Rejected or
 * Ignored one, nothing was written, and the message says so.
 */
@Getter
public final class AfterCommitException extends StateMachineException {

    private final MachineKey<?, ?, ?> key;
    private final Object entity;
    private final Enum<?> from;
    private final Enum<?> to;
    /** The hook's name, or {@code "listener"}. */
    private final String step;

    /** A step that failed after phase 2 had written the states. */
    public AfterCommitException(MachineKey<?, ?, ?> key, Object entity, Enum<?> from, Enum<?> to, String step, Throwable cause) {
        this(key, entity, from, to, step, true, cause);
    }

    /**
     * A step that failed after a call that wrote the states when {@code written} is true, or after a call that wrote
     * no state when it is false: a listener notified of a Rejected or Ignored outcome.
     */
    public AfterCommitException(MachineKey<?, ?, ?> key, Object entity, Enum<?> from, Enum<?> to, String step,
                                boolean written, Throwable cause) {
        super(describe(key, from, to, step, written), cause);
        this.key = key;
        this.entity = Objects.requireNonNull(entity, "entity");
        this.from = from;
        this.to = to;
        this.step = step;
    }

    private static String describe(MachineKey<?, ?, ?> key, Enum<?> from, Enum<?> to, String step, boolean written) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(step, "step");
        String call = key + " " + from + " → " + to;
        return written
                ? "the states of " + call + " are already written, but the post-commit step '" + step + "' failed"
                : "the call on " + call + " wrote no state, and the post-commit step '" + step + "' failed";
    }
}
