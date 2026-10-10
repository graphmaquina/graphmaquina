package io.github.graphmaquina.error;

import io.github.graphmaquina.api.MachineKey;
import java.util.Objects;
import lombok.Getter;
import lombok.NonNull;

/**
 * The state getter returned null when the machine read the entity's state. Inside a call, a null root state is the
 * cause of an abort in RESOLVE, and a cascaded peer's of one in ACTION, as is a sub-machine's, read when its parent
 * enters the bound state. A parent's, read to check the binding of a sub-machine, is the cause of an abort in RESOLVE
 * when the sub-machine is the root and in ACTION inside a cascade or an exit. A watch's owner lookup reports it as it
 * is, in its {@code Reaction.Aborted}.
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
