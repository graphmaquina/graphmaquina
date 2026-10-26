package io.github.graphmaquina.error;

import io.github.graphmaquina.api.MachineKey;
import java.util.Optional;
import lombok.Getter;
import lombok.NonNull;

/**
 * An eligibility check that could not reach an answer. It is no {@link CallFailedException}: a check is not a call, so
 * it carries no call, phase, states written, journal or flow. It reports the key of the machine, the entity and the
 * identifier the check asked about; the state it read and the guard it was running, each empty while unknown; and the
 * cause, when one is present, as {@link #getCause()}. The caller composes the message, which is kept as given.
 */
@Getter
public final class EligibilityCheckException extends StateMachineException {

    private final MachineKey<?, ?, ?> key;
    private final Object entity;
    private final Enum<?> transition;

    /** The state the check read; empty when it failed before reading it. */
    private final Optional<Enum<?>> from;

    /** The guard the check was running; empty when it failed outside one. */
    private final Optional<String> guard;

    public EligibilityCheckException(@NonNull MachineKey<?, ?, ?> key, @NonNull Object entity,
                                     @NonNull Enum<?> transition, @NonNull Optional<Enum<?>> from,
                                     @NonNull Optional<String> guard, @NonNull String message,
                                     @NonNull Optional<Throwable> cause) {
        super(message);
        this.key = key;
        this.entity = entity;
        this.transition = transition;
        this.from = from;
        this.guard = guard;
        // StateMachineException(message, cause) refuses a null cause, so the cause, when there is one, is set here.
        if (cause.isPresent()) {
            initCause(cause.get());
        }
    }
}
