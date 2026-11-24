package io.github.graphmaquina.api;

import java.util.List;
import lombok.NonNull;

/**
 * Named guard. Mirrors {@link ActionRef}, but wraps {@link TransitionGuard} and {@link AsyncTransitionGuard}, with the
 * throwable types it lets reach the caller as themselves: when the guard of the root step of a call the application
 * makes, or of a check, throws or completes its stage with an instance of one of them, the call aborts as for any
 * guard that throws and then throws that instance in place of its {@code TransitionAbortedException}, and the check
 * throws it in place of its {@code EligibilityCheckException}. The DSL and the processor admit the types that
 * {@code failsOn} admits (SG302).
 */
public sealed interface GuardRef<E, S extends Enum<S>, T extends Enum<T>> permits GuardRef.Sync, GuardRef.Async {

    String name();

    boolean isAsync();

    /** The throwable types that reach the caller as themselves, in declaration order; empty when there is none. */
    List<Class<? extends Throwable>> propagates();

    /** A synchronous {@link GuardRef}. */
    record Sync<E, S extends Enum<S>, T extends Enum<T>>(@NonNull String name, @NonNull TransitionGuard<E, S, T> guard,
                                                         @NonNull List<Class<? extends Throwable>> propagates)
            implements GuardRef<E, S, T> {
        public Sync {
            if (name.isBlank()) {
                throw new IllegalArgumentException("name must not be blank");
            }
            propagates = List.copyOf(propagates);
        }

        /** A synchronous guard that propagates nothing. */
        public Sync(String name, TransitionGuard<E, S, T> guard) {
            this(name, guard, List.of());
        }

        @Override
        public boolean isAsync() {
            return false;
        }
    }

    /** An asynchronous {@link GuardRef}. */
    record Async<E, S extends Enum<S>, T extends Enum<T>>(@NonNull String name,
                                                          @NonNull AsyncTransitionGuard<E, S, T> guard,
                                                          @NonNull List<Class<? extends Throwable>> propagates)
            implements GuardRef<E, S, T> {
        public Async {
            if (name.isBlank()) {
                throw new IllegalArgumentException("name must not be blank");
            }
            propagates = List.copyOf(propagates);
        }

        /** An asynchronous guard that propagates nothing. */
        public Async(String name, AsyncTransitionGuard<E, S, T> guard) {
            this(name, guard, List.of());
        }

        @Override
        public boolean isAsync() {
            return true;
        }
    }
}
