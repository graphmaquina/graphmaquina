package io.github.graphmaquina.dsl;

import io.github.graphmaquina.api.AsyncTransitionGuard;
import io.github.graphmaquina.api.GuardRef;
import io.github.graphmaquina.api.TransitionGuard;
import java.util.Optional;

/** Draft of one guard declared on a cell, before its name is resolved. */
record GuardDraft<E, S extends Enum<S>>(Optional<String> explicitName,
                                         Optional<TransitionGuard<E, S>> sync,
                                         Optional<AsyncTransitionGuard<E, S>> async) {

    Object instance() {
        if (sync.isPresent()) {
            return sync.get();
        }
        return async.orElseThrow();
    }

    GuardRef<E, S> toRef(String name) {
        if (sync.isPresent()) {
            return new GuardRef.Sync<>(name, sync.get());
        }
        return new GuardRef.Async<>(name, async.orElseThrow());
    }
}
