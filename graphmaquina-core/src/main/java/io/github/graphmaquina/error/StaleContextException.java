package io.github.graphmaquina.error;

import io.github.graphmaquina.api.MachineKey;
import java.util.Objects;
import lombok.Getter;
import lombok.NonNull;

/**
 * A context's {@code peer(...)}, {@code cascade(...)} or {@code cascadeAll(...)}, or a peer view's
 * {@code stateOf(...)}, was called after its transaction had ended. The context's plain accessors, such as
 * {@code entity()}, do not check.
 */
@Getter
public final class StaleContextException extends StateMachineException {

    private final MachineKey<?, ?, ?> key;
    private final Object entity;

    public StaleContextException(MachineKey<?, ?, ?> key, @NonNull Object entity) {
        super("the context of " + Objects.requireNonNull(key, "key") + " is no longer valid: the transaction ended");
        this.key = key;
        this.entity = entity;
    }
}
