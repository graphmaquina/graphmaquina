package io.stategraph.api;

import java.util.Objects;

/** Named guard. Mirrors {@link ActionRef}, but wraps {@link TransitionGuard} and {@link AsyncTransitionGuard}. */
public sealed interface GuardRef<E, S extends Enum<S>> permits GuardRef.Sync, GuardRef.Async {

    String name();

    boolean isAsync();

    /** A synchronous {@link GuardRef}. */
    record Sync<E, S extends Enum<S>>(String name, TransitionGuard<E, S> guard) implements GuardRef<E, S> {
        public Sync {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(guard, "guard");
            if (name.isBlank()) {
                throw new IllegalArgumentException("name must not be blank");
            }
        }

        @Override
        public boolean isAsync() {
            return false;
        }
    }

    /** An asynchronous {@link GuardRef}. */
    record Async<E, S extends Enum<S>>(String name, AsyncTransitionGuard<E, S> guard) implements GuardRef<E, S> {
        public Async {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(guard, "guard");
            if (name.isBlank()) {
                throw new IllegalArgumentException("name must not be blank");
            }
        }

        @Override
        public boolean isAsync() {
            return true;
        }
    }
}
