package io.github.graphmaquina.api;

import lombok.NonNull;

/** Journal entry: an action that actually ran, with the transition it belonged to and its final status. */
public record ActionRecord(@NonNull MachineKey<?, ?, ?> key, @NonNull Object entity, @NonNull Enum<?> from,
                           @NonNull Enum<?> transition, @NonNull Enum<?> to, @NonNull String action, int depth,
                           @NonNull ActionStatus status) {
}
