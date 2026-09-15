package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.AsyncStateMachine;
import io.github.graphmaquina.api.Outcome;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/** Runs the whole operation on the machine's executor. Never runs, nor throws, on the caller's thread. */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class DefaultAsyncStateMachine<E, S extends Enum<S>> implements AsyncStateMachine<E, S> {

    private final DefaultStateMachine<E, S> machine;

    @Override
    public CompletableFuture<Outcome<E, S>> transitionTo(E entity, S target) {
        return transitionTo(entity, target, null);
    }

    @Override
    public CompletableFuture<Outcome<E, S>> transitionTo(E entity, S target, Object input) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(target, "target");
        return submit(() -> machine.transitionTo(entity, target, input));
    }

    @Override
    public CompletableFuture<Outcome<E, S>> reevaluate(E entity) {
        Objects.requireNonNull(entity, "entity");
        return submit(() -> machine.reevaluate(entity));
    }

    private CompletableFuture<Outcome<E, S>> submit(Supplier<Outcome<E, S>> body) {
        try {
            return CompletableFuture.supplyAsync(body, machine.executor());
        } catch (RejectedExecutionException e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
