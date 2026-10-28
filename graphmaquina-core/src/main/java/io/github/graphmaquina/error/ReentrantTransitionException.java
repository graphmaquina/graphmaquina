package io.github.graphmaquina.error;

import io.github.graphmaquina.api.MachineKey;
import java.util.Objects;
import lombok.Getter;
import lombok.NonNull;

/**
 * execute, reevaluate, advance or an eligibility check was called on a thread that is already running a root
 * transaction or an eligibility check, evaluating a watch or notifying an observer, from one of the callbacks that run
 * there: a guard, an action, a state accessor, an identity function or a sub-machine's childOf or parentOf inside the
 * transaction, or any of them but the action inside the check, which runs none; a watch's peersOf, ownerOf or
 * condition, or a state accessor or identity function the engine calls while it evaluates the watch; or a registry
 * observer's callback. A thread runs one transition or check at a time, whatever the machine or the entity of the
 * second one, and a watch selects an edge without running one. Only an action can move other entities inside the
 * transaction, through its context's cascade. After-commit hooks run once the transaction has released the thread, so
 * they can start transitions and checks; a watch reaction runs the edge its rule selected as a root of its own, once
 * the watch's functions are done. Any of these callbacks that submits a call through {@code async(...)} gets a separate
 * call on the executor's thread, unless the call runs on the callback's own thread, in which case the returned future
 * fails with this exception as its cause: an executor that runs tasks inline runs it there, and so can a wait of the
 * callback that runs a ForkJoinPool's queued tasks, as joining a ForkJoinTask or waiting for the pool's quiescence
 * does. CompletableFuture's get and join never run such a call.
 */
@Getter
public final class ReentrantTransitionException extends StateMachineException {

    private final MachineKey<?, ?, ?> key;
    private final Object entity;

    public ReentrantTransitionException(MachineKey<?, ?, ?> key, @NonNull Object entity) {
        super("execute/reevaluate/advance/eligibility on " + Objects.requireNonNull(key, "key")
                + " from within a transition, an eligibility check, a watch evaluation or an observer callback already"
                + " in progress on this thread: a guard, an action, a sub-machine's childOf or parentOf, a watch's"
                + " peersOf, ownerOf or condition, a state accessor, an identity function or an observer cannot start"
                + " another transition or eligibility check; an action moves its peers with ctx.cascade(...)");
        this.key = key;
        this.entity = entity;
    }
}
