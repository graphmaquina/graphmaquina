package io.stategraph.error;

import io.stategraph.api.MachineKey;
import java.util.Objects;
import lombok.Getter;

/**
 * A post-commit step (an after-commit hook or the registry listener) threw after phase 2 had already
 * written the states in memory. Nothing is rolled back; the caller's transaction decides.
 */
@Getter
public final class AfterCommitException extends StateMachineException {

    private final MachineKey<?, ?> key;
    private final Object entity;
    private final Enum<?> from;
    private final Enum<?> to;
    /** The hook's name, or {@code "listener"}. */
    private final String step;

    public AfterCommitException(MachineKey<?, ?> key, Object entity, Enum<?> from, Enum<?> to, String step, Throwable cause) {
        super(describe(key, from, to, step), cause);
        this.key = key;
        this.entity = Objects.requireNonNull(entity, "entity");
        this.from = from;
        this.to = to;
        this.step = step;
    }

    private static String describe(MachineKey<?, ?> key, Enum<?> from, Enum<?> to, String step) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(step, "step");
        return "the states of " + key + " " + from + " → " + to + " are already written, but the post-commit step '" + step + "' failed";
    }
}
