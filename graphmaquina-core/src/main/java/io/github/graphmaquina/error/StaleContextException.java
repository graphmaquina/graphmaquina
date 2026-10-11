package io.github.graphmaquina.error;

import io.github.graphmaquina.api.MachineKey;
import java.util.Objects;
import lombok.Getter;
import lombok.NonNull;

/**
 * A context's {@code peer(...)}, {@code cascade(...)}, {@code cascadeAll(...)} or {@code flow()}, a peer view's
 * {@code stateOf(...)}, or an operation of the flow a context handed out, {@code get}, {@code keys}, {@code entity},
 * {@code trail}, {@code put} or {@code remove}, was called after its transaction had ended. The context's plain
 * accessors, such as {@code entity()}, do not check. An operation of the flow a post-commit hook received from its
 * {@code CommitContext} throws it too once that hook returned or threw, with the machine key and the entity of the
 * hop the hook ran for; the transaction had ended before the hook ran.
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
