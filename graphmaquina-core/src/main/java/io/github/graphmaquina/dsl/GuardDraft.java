package io.github.graphmaquina.dsl;

import io.github.graphmaquina.api.AsyncTransitionGuard;
import io.github.graphmaquina.api.GuardRef;
import io.github.graphmaquina.api.TransitionGuard;
import java.util.Optional;

/** Draft of one guard declared on an edge, before its name is resolved. */
record GuardDraft<E, S extends Enum<S>, T extends Enum<T>>(Optional<String> explicitName,
                                                            Optional<TransitionGuard<E, S, T>> sync,
                                                            Optional<AsyncTransitionGuard<E, S, T>> async) {

    Object instance() {
        if (sync.isPresent()) {
            return sync.get();
        }
        return async.orElseThrow();
    }

    GuardRef<E, S, T> toRef(String name) {
        if (sync.isPresent()) {
            return new GuardRef.Sync<>(name, sync.get());
        }
        return new GuardRef.Async<>(name, async.orElseThrow());
    }
}
