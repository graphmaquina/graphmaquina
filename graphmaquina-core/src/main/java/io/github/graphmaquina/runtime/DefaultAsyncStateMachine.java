package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.AsyncStateMachine;
import io.github.graphmaquina.api.Outcome;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Submits the whole operation to the machine's executor through {@link Submit}, and the executor decides the thread:
 * one that runs tasks inline runs it on the caller's thread. A null argument throws on the caller's thread; a refused
 * submission fails the future.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class DefaultAsyncStateMachine<E, S extends Enum<S>, T extends Enum<T>> implements AsyncStateMachine<E, S, T> {

    private final DefaultStateMachine<E, S, T> machine;

    @Override
    public CompletableFuture<Outcome<E, S, T>> execute(@NonNull E entity, @NonNull T transition) {
        return submit(() -> machine.execute(entity, transition));
    }

    @Override
    public CompletableFuture<Outcome<E, S, T>> execute(@NonNull E entity, @NonNull T transition,
            @NonNull Object input) {
        return submit(() -> machine.execute(entity, transition, input));
    }

    @Override
    public CompletableFuture<Outcome<E, S, T>> reevaluate(@NonNull E entity) {
        return submit(() -> machine.reevaluate(entity));
    }

    @Override
    public CompletableFuture<Outcome<E, S, T>> advance(@NonNull E entity) {
        return submit(() -> machine.advance(entity));
    }

    private CompletableFuture<Outcome<E, S, T>> submit(Supplier<Outcome<E, S, T>> body) {
        return machine.submit(body);
    }
}
