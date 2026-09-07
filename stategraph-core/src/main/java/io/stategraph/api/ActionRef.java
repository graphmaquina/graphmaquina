package io.stategraph.api;

import java.util.Objects;

/** Named action. The name is what gets drawn and what messages carry. */
public sealed interface ActionRef<E, S extends Enum<S>> permits ActionRef.Sync, ActionRef.Async {

    String name();

    boolean isAsync();

    /** A synchronous {@link ActionRef}. */
    record Sync<E, S extends Enum<S>>(String name, TransitionAction<E, S> action) implements ActionRef<E, S> {
        public Sync {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(action, "action");
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
    record Async<E, S extends Enum<S>>(String name, AsyncTransitionAction<E, S> action) implements ActionRef<E, S> {
        public Async {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(action, "action");
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
