package io.github.graphmaquina.error;

import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.internal.Texts;
import java.util.Objects;
import lombok.Getter;
import lombok.NonNull;

/**
 * Two distinct objects with the same logical identity within a transaction. Its message prints the identity after the
 * machine key and its {@code #}, as the cascade path of an abort does: {@code <toString threw <class name>>} in place
 * of an identity whose {@code toString()} throws anything but an Error.
 */
@Getter
public final class AmbiguousIdentityException extends StateMachineException {

    private final MachineKey<?, ?, ?> key;
    private final Object id;
    private final Object first;
    private final Object second;

    public AmbiguousIdentityException(MachineKey<?, ?, ?> key, Object id, @NonNull Object first, @NonNull Object second) {
        super("two distinct objects with the identity " + Objects.requireNonNull(key, "key") + "#"
                + Texts.identity(Objects.requireNonNull(id, "id")) + " within the same transaction");
        this.key = key;
        this.id = id;
        this.first = first;
        this.second = second;
    }
}
