package io.github.graphmaquina.api;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import lombok.NonNull;

/**
 * Named post-commit hook of a transition. It runs on the calling thread after phase 2 wrote every state of the
 * transaction and before the watch reactions. If it throws an Exception, checked ones included, the states stay
 * written in memory and the transaction surfaces {@code AfterCommitException}: the application's own transaction
 * decides what to do. An Error propagates unchanged. Either way, the later hooks, the watch reactions and the
 * listener of that call do not run.
 *
 * @param <E> entity type
 * @param <S> state enum
 * @param <T> transition identifier enum
 * @param name the name carried by diagnostics
 * @param hook the callback, receiving the entity and the metadata of the committed transition
 */
public record HookRef<E, S extends Enum<S>, T extends Enum<T>>(
        @NonNull String name, @NonNull BiConsumer<? super E, ? super CommitContext<S, T>> hook) {

    public HookRef {
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }

    /** Adapter for a hook that only wants the entity. */
    public static <E, S extends Enum<S>, T extends Enum<T>> HookRef<E, S, T> of(@NonNull String name,
                                                                                @NonNull Consumer<? super E> hook) {
        return new HookRef<>(name, (E entity, CommitContext<S, T> context) -> hook.accept(entity));
    }

    public void run(E entity, CommitContext<S, T> context) {
        hook.accept(entity, context);
    }
}
