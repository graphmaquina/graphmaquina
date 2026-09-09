package io.github.graphmaquina.api;

import java.util.Optional;
import lombok.NonNull;

/** An annotated hop within the transaction. {@code convergent} marks the idempotent no-op of a cascade. */
public record TransitionRecord<E, S extends Enum<S>>(@NonNull MachineKey<E, S> key, @NonNull E entity, @NonNull S from,
                                                     @NonNull S to, @NonNull EdgeKind kind,
                                                     @NonNull Optional<String> action, int depth, boolean convergent) {
}
