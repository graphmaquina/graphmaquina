package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.AsyncStateMachine;
import io.github.graphmaquina.api.Outcome;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/**
 * Submits the whole operation to the machine's executor, which decides the thread: one that runs tasks inline runs
 * it on the caller's thread. A null argument throws on the caller's thread; a refused submission fails the future.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class DefaultAsyncStateMachine<E, S extends Enum<S>, T extends Enum<T>> implements AsyncStateMachine<E, S, T> {

    private final DefaultStateMachine<E, S, T> machine;

    @Override
    public CompletableFuture<Outcome<E, S, T>> execute(E entity, T transition) {
        return execute(entity, transition, null);
    }

    @Override
    public CompletableFuture<Outcome<E, S, T>> execute(E entity, T transition, Object input) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(transition, "transition");
        return submit(() -> machine.execute(entity, transition, input));
    }

    @Override
    public CompletableFuture<Outcome<E, S, T>> reevaluate(E entity) {
        Objects.requireNonNull(entity, "entity");
        return submit(() -> machine.reevaluate(entity));
    }

    private CompletableFuture<Outcome<E, S, T>> submit(Supplier<Outcome<E, S, T>> body) {
        try {
            return CompletableFuture.supplyAsync(body, machine.executor());
        } catch (RejectedExecutionException e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
