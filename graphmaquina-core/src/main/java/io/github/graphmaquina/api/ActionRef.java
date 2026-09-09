package io.github.graphmaquina.api;

import lombok.NonNull;

/** Named action. The name is what gets drawn and what messages carry. */
public sealed interface ActionRef<E, S extends Enum<S>> permits ActionRef.Sync, ActionRef.Async {

    String name();

    boolean isAsync();

    /** A synchronous {@link ActionRef}. */
    record Sync<E, S extends Enum<S>>(@NonNull String name, @NonNull TransitionAction<E, S> action) implements ActionRef<E, S> {
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

    /** An asynchronous {@link ActionRef}. */
    record Async<E, S extends Enum<S>>(@NonNull String name, @NonNull AsyncTransitionAction<E, S> action) implements ActionRef<E, S> {
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
