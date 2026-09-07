package io.stategraph.error;

import io.stategraph.api.MachineKey;
import java.util.Objects;
import lombok.Getter;
import lombok.NonNull;

/** transitionTo/reevaluate was called on an entity from within a transition already running for it. */
@Getter
public final class ReentrantTransitionException extends StateMachineException {

    private final MachineKey<?, ?> key;
    private final Object entity;

    public ReentrantTransitionException(MachineKey<?, ?> key, @NonNull Object entity) {
        super("transitionTo/reevaluate on " + Objects.requireNonNull(key, "key")
                + " from within a transition already in progress; use ctx.cascade(...)");
        this.key = key;
        this.entity = entity;
    }
}
