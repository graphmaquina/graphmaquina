package io.stategraph.api;

import java.util.Objects;
import java.util.Optional;

/** An annotated hop within the transaction. {@code convergent} marks the idempotent no-op of a cascade. */
public record TransitionRecord<E, S extends Enum<S>>(MachineKey<E, S> key, E entity, S from, S to, EdgeKind kind,
                                                     Optional<String> action, int depth, boolean convergent) {

    public TransitionRecord {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(action, "action");
    }
}
