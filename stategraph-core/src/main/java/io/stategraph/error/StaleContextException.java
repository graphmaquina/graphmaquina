package io.stategraph.error;

import io.stategraph.api.MachineKey;
import java.util.Objects;

/** A context or peer view was used after its transaction had already ended. */
public final class StaleContextException extends StateMachineException {

    private final MachineKey<?, ?> key;
    private final Object entity;

    public StaleContextException(MachineKey<?, ?> key, Object entity) {
        super("the context of " + Objects.requireNonNull(key, "key") + " is no longer valid: the transaction ended");
        this.key = key;
        this.entity = Objects.requireNonNull(entity, "entity");
    }

    public MachineKey<?, ?> key() { return key; }

    public Object entity() { return entity; }
}
