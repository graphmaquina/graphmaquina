package io.stategraph.error;

import io.stategraph.api.MachineKey;
import java.util.Objects;
import lombok.Getter;
import lombok.NonNull;

/** The entity's state field was null when the machine tried to read it. */
@Getter
public final class UninitializedStateException extends StateMachineException {

    private final MachineKey<?, ?> key;
    private final Object entity;

    public UninitializedStateException(MachineKey<?, ?> key, @NonNull Object entity) {
        super("the state of " + Objects.requireNonNull(key, "key")
                + " is null: set the state in the entity's constructor, for example with definition().initial()");
        this.key = key;
        this.entity = entity;
    }
}
