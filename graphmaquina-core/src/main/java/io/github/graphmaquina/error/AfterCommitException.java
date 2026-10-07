package io.github.graphmaquina.error;

import io.github.graphmaquina.api.MachineKey;
import java.util.Objects;
import lombok.Getter;

/**
 * An after-commit hook threw an exception, checked ones included; an Error propagates unchanged instead. A hook runs
 * only for committed hops, so it always fails after phase 2 had written the states in memory. Nothing is rolled back:
 * the caller's transaction decides. The hooks after it and the watch reactions of the call do not run, and the call
 * ends in this exception.
 */
@Getter
public final class AfterCommitException extends StateMachineException {

    private final MachineKey<?, ?, ?> key;
    private final Object entity;
    private final Enum<?> from;
    private final Enum<?> to;
    /** The hook's name. */
    private final String step;

    /** A hook that failed after phase 2 had written the states. */
    public AfterCommitException(MachineKey<?, ?, ?> key, Object entity, Enum<?> from, Enum<?> to, String step, Throwable cause) {
        super(describe(key, from, to, step), cause);
        this.key = key;
        this.entity = Objects.requireNonNull(entity, "entity");
        this.from = from;
        this.to = to;
        this.step = step;
    }

    private static String describe(MachineKey<?, ?, ?> key, Enum<?> from, Enum<?> to, String step) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(step, "step");
        return "the states of " + key + " " + from + " → " + to + " are already written, but the post-commit step '"
                + step + "' failed";
    }
}
