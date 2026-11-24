package io.github.graphmaquina.dsl;

import io.github.graphmaquina.api.AsyncTransitionGuard;
import io.github.graphmaquina.api.GuardRef;
import io.github.graphmaquina.api.TransitionGuard;
import java.util.List;
import java.util.Optional;

/** Draft of one guard declared on an edge, before its name is resolved, with the types it propagates. */
record GuardDraft<E, S extends Enum<S>, T extends Enum<T>>(Optional<String> explicitName,
                                                            Optional<TransitionGuard<E, S, T>> sync,
                                                            Optional<AsyncTransitionGuard<E, S, T>> async,
                                                            List<Class<? extends Throwable>> propagates) {

    /** A draft of a guard that propagates nothing. */
    GuardDraft(Optional<String> explicitName, Optional<TransitionGuard<E, S, T>> sync,
               Optional<AsyncTransitionGuard<E, S, T>> async) {
        this(explicitName, sync, async, List.of());
    }

    Object instance() {
        if (sync.isPresent()) {
            return sync.get();
        }
        return async.orElseThrow();
    }

    GuardRef<E, S, T> toRef(String name) {
        if (sync.isPresent()) {
            return new GuardRef.Sync<>(name, sync.get(), propagates);
        }
        return new GuardRef.Async<>(name, async.orElseThrow(), propagates);
    }
}
