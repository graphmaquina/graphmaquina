package io.stategraph.error;

import io.stategraph.api.MachineKey;
import java.util.Objects;
import lombok.Getter;
import lombok.NonNull;

/** Two distinct objects with the same logical identity within a transaction. */
@Getter
public final class AmbiguousIdentityException extends StateMachineException {

    private final MachineKey<?, ?> key;
    private final Object id;
    private final Object first;
    private final Object second;

    public AmbiguousIdentityException(MachineKey<?, ?> key, Object id, @NonNull Object first, @NonNull Object second) {
        super("two distinct objects with the identity " + Objects.requireNonNull(key, "key") + "#"
                + Objects.requireNonNull(id, "id") + " within the same transaction");
        this.key = key;
        this.id = id;
        this.first = first;
        this.second = second;
    }
}
