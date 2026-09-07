package io.stategraph.error;

import io.stategraph.api.ActionRecord;
import io.stategraph.api.MachineKey;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Common payload of every abort: what was being attempted, how far the cascade had gone, and which actions managed to run. */
public record AbortDetails(MachineKey<?, ?> key, Object entity, Enum<?> from, Enum<?> to, Optional<String> action,
                           List<String> cascadePath, List<ActionRecord> journal) {

    public AbortDetails {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(cascadePath, "cascadePath");
        Objects.requireNonNull(journal, "journal");
        cascadePath = List.copyOf(cascadePath);
        journal = List.copyOf(journal);
    }
}
