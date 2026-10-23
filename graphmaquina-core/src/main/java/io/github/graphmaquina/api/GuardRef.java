package io.github.graphmaquina.api;

import lombok.NonNull;

/** Named guard. Mirrors {@link ActionRef}, but wraps {@link TransitionGuard} and {@link AsyncTransitionGuard}. */
public sealed interface GuardRef<E, S extends Enum<S>, T extends Enum<T>> permits GuardRef.Sync, GuardRef.Async {

    String name();

    boolean isAsync();

    /** A synchronous {@link GuardRef}. */
    record Sync<E, S extends Enum<S>, T extends Enum<T>>(@NonNull String name, @NonNull TransitionGuard<E, S, T> guard)
            implements GuardRef<E, S, T> {
        public Sync {
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
    record Async<E, S extends Enum<S>, T extends Enum<T>>(@NonNull String name,
                                                          @NonNull AsyncTransitionGuard<E, S, T> guard)
            implements GuardRef<E, S, T> {
        public Async {
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
