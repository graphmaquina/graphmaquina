package io.github.graphmaquina.dsl;

import io.github.graphmaquina.api.CommitContext;
import io.github.graphmaquina.api.HookRef;
import java.util.function.Consumer;
import java.util.function.Function;

/** Binds the hook to its immutable cell metadata once all terminal declarations are known. */
record HookDraft<E, S extends Enum<S>>(String name, Function<CommitContext<S>, Consumer<? super E>> bind) {

    HookDraft {
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }

    HookRef<E> toRef(CommitContext<S> context) {
        return new HookRef<>(name, bind.apply(context));
    }
}
