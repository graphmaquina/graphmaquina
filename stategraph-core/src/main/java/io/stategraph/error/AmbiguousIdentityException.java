package io.stategraph.error;

import io.stategraph.api.MachineKey;
import java.util.Objects;

/** Two distinct objects with the same logical identity within a transaction. */
public final class AmbiguousIdentityException extends StateMachineException {

    private final MachineKey<?, ?> key;
    private final Object id;
    private final Object first;
    private final Object second;

    public AmbiguousIdentityException(MachineKey<?, ?> key, Object id, Object first, Object second) {
        super("two distinct objects with the identity " + Objects.requireNonNull(key, "key") + "#"
                + Objects.requireNonNull(id, "id") + " within the same transaction");
        this.key = key;
        this.id = id;
        this.first = Objects.requireNonNull(first, "first");
        this.second = Objects.requireNonNull(second, "second");
    }

    public MachineKey<?, ?> key() { return key; }

    public Object id() { return id; }

    public Object first() { return first; }

    public Object second() { return second; }
}
