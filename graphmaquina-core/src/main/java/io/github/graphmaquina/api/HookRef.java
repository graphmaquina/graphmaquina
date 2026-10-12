package io.github.graphmaquina.api;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import lombok.NonNull;

/**
 * Named post-commit hook of a transition. It runs on the calling thread after phase 2 wrote every state of the
 * transaction and before the watch reactions. If it throws a throwable that is not an Error, a checked exception
 * included, the states stay written in memory and the transaction surfaces {@code AfterCommitException}, which carries
 * the hook's name ({@code hook()}), the committed hop it ran for ({@code hop()}), every hop the call committed
 * ({@code records()}), every sub-machine it started ({@code starts()}) and the call's flow, with what the hooks
 * wrote, this hook's writes before it threw included ({@code flow()}): the application's own transaction decides
 * what to do. An Error propagates unchanged. Either way, the later hooks and the watch reactions of that call do not
 * run.
 *
 * @param <E> entity type
 * @param <S> state enum
 * @param <T> transition identifier enum
 * @param name the name carried by diagnostics
 * @param hook the callback, receiving the entity and the {@link CommitContext} of the committed hop: its metadata and
 *             the call's flow, which works only while the callback runs
 */
public record HookRef<E, S extends Enum<S>, T extends Enum<T>>(
        @NonNull String name, @NonNull BiConsumer<? super E, ? super CommitContext<S, T>> hook) {

    public HookRef {
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }

    /** Adapter for a hook that only wants the entity: it never sees the context, so never the call's flow. */
    public static <E, S extends Enum<S>, T extends Enum<T>> HookRef<E, S, T> of(@NonNull String name,
                                                                                @NonNull Consumer<? super E> hook) {
        return new HookRef<>(name, (E entity, CommitContext<S, T> context) -> hook.accept(entity));
    }

    public void run(E entity, CommitContext<S, T> context) {
        hook.accept(entity, context);
    }
}
