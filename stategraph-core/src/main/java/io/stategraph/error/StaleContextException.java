package io.stategraph.error;

import io.stategraph.api.MachineKey;
import java.util.Objects;
import lombok.Getter;
import lombok.NonNull;

/** A context or peer view was used after its transaction had already ended. */
@Getter
public final class StaleContextException extends StateMachineException {

    private final MachineKey<?, ?> key;
    private final Object entity;

    public StaleContextException(MachineKey<?, ?> key, @NonNull Object entity) {
        super("the context of " + Objects.requireNonNull(key, "key") + " is no longer valid: the transaction ended");
        this.key = key;
        this.entity = entity;
    }
}
