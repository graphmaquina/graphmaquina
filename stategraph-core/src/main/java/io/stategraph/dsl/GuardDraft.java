package io.stategraph.dsl;

import io.stategraph.api.AsyncTransitionGuard;
import io.stategraph.api.GuardRef;
import io.stategraph.api.TransitionGuard;
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
