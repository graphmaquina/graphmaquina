package io.github.graphmaquina.error;

import io.github.graphmaquina.api.MachineKey;
import java.util.Objects;
import lombok.Getter;
import lombok.NonNull;

/**
 * execute or reevaluate was called on a thread that is already running a root transaction, evaluating a watch or
 * notifying an observer, from one of the callbacks that run there: a guard, an action, a state accessor or an
 * identity function inside the transaction; a watch's peersOf, ownerOf or condition, or a state accessor or identity
 * function the engine calls while it evaluates the watch; or a registry observer's callback. A thread runs one
 * transition at a time, whatever the machine or the entity of the second call, and a watch selects an edge without
 * running one. Only an action can move other entities inside the transaction, through its context's cascade.
 * After-commit hooks run once the transaction has released the thread, so they can start transitions;
 * a watch reaction runs the edge its rule selected as a root of its own, once the watch's functions are done.
 * A call that one of those callbacks submits through {@code async(...)} runs as a separate call on the executor's
 * thread.
 */
@Getter
public final class ReentrantTransitionException extends StateMachineException {

    private final MachineKey<?, ?, ?> key;
    private final Object entity;

    public ReentrantTransitionException(MachineKey<?, ?, ?> key, @NonNull Object entity) {
        super("execute/reevaluate on " + Objects.requireNonNull(key, "key")
                + " from within a transition, a watch evaluation or an observer callback already in progress on this"
                + " thread: a guard, an action, a watch's peersOf, ownerOf or condition, a state accessor, an identity"
                + " function or an observer cannot start another transition; an action moves its peers with"
                + " ctx.cascade(...)");
        this.key = key;
        this.entity = entity;
    }
}
