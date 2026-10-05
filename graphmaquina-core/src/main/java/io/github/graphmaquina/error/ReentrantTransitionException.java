package io.github.graphmaquina.error;

import io.github.graphmaquina.api.MachineKey;
import java.util.Objects;
import lombok.Getter;
import lombok.NonNull;

/**
 * execute or reevaluate was called on a thread that is already running a root transaction, from one of the callbacks
 * that run inside it: a guard, an action, a state accessor or an identity function. A thread runs one transition at a
 * time, whatever the machine or the entity of the second call. Only an action can move other entities inside the
 * transaction, through its context's cascade. After-commit hooks, watch reactions and the listener run once the
 * transaction has released the thread, so they can start transitions.
 */
@Getter
public final class ReentrantTransitionException extends StateMachineException {

    private final MachineKey<?, ?, ?> key;
    private final Object entity;

    public ReentrantTransitionException(MachineKey<?, ?, ?> key, @NonNull Object entity) {
        super("execute/reevaluate on " + Objects.requireNonNull(key, "key")
                + " from within a transition already in progress on this thread: a guard, an action, a state accessor"
                + " or an identity function cannot start another transition; an action moves its peers with"
                + " ctx.cascade(...)");
        this.key = key;
        this.entity = entity;
    }
}
