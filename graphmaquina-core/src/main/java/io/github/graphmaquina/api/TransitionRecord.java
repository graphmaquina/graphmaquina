package io.github.graphmaquina.api;

import java.util.Optional;
import lombok.NonNull;

/** An annotated hop within the transaction: which edge ran, on which entity, and how deep in the cascade. */
public record TransitionRecord<E, S extends Enum<S>, T extends Enum<T>>(@NonNull MachineKey<E, S, T> key, @NonNull E entity,
                                                                        @NonNull S from, @NonNull T transition, @NonNull S to,
                                                                        @NonNull TransitionKind kind,
                                                                        @NonNull Optional<String> action, int depth) {
}
