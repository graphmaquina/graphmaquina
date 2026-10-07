package io.github.graphmaquina.error;

import io.github.graphmaquina.api.MachineKey;
import java.util.Objects;
import lombok.Getter;
import lombok.NonNull;

/**
 * The state getter returned null when the machine read the entity's state. Inside a call it is the cause of an abort
 * in RESOLVE.
 */
@Getter
public final class UninitializedStateException extends StateMachineException {

    private final MachineKey<?, ?, ?> key;
    private final Object entity;

    public UninitializedStateException(MachineKey<?, ?, ?> key, @NonNull Object entity) {
        super("the state of " + Objects.requireNonNull(key, "key")
                + " is null: set the state in the entity's constructor, for example with definition().initial()");
        this.key = key;
        this.entity = entity;
    }
}
