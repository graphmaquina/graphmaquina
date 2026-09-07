package io.stategraph.api;

import java.util.Objects;

/** Journal entry: an action that actually ran, with its final status. */
public record ActionRecord(MachineKey<?, ?> key, Object entity, Enum<?> from, Enum<?> to,
                           String action, int depth, ActionStatus status) {

    public ActionRecord {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(status, "status");
    }
}
