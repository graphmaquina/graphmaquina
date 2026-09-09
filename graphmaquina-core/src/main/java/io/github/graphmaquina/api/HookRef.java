package io.github.graphmaquina.api;

import java.util.function.Consumer;
import lombok.NonNull;

/**
 * Named post-commit hook of a cell. It runs on the calling thread after phase 2 wrote every state of the
 * transaction and before the watch reactions. If it throws, the states stay written in memory and the
 * transaction surfaces {@code AfterCommitException}: the application's own transaction decides what to do.
 */
public record HookRef<E>(@NonNull String name, @NonNull Consumer<? super E> hook) {

    public HookRef {
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }
}
