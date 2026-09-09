package io.github.graphmaquina.error;

import io.github.graphmaquina.api.ActionRecord;
import io.github.graphmaquina.api.MachineKey;
import java.util.List;
import java.util.Optional;
import lombok.NonNull;

/** Common payload of every abort: what was being attempted, how far the cascade had gone, and which actions managed to run. */
public record AbortDetails(@NonNull MachineKey<?, ?> key, @NonNull Object entity, @NonNull Enum<?> from, @NonNull Enum<?> to,
                           @NonNull Optional<String> action, @NonNull List<String> cascadePath, @NonNull List<ActionRecord> journal) {

    public AbortDetails {
        cascadePath = List.copyOf(cascadePath);
        journal = List.copyOf(journal);
    }
}
