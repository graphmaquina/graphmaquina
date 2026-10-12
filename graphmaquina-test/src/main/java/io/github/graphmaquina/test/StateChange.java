package io.github.graphmaquina.test;

import io.github.graphmaquina.api.MachineKey;
import java.util.Objects;
import java.util.Optional;

/**
 * One tracked entity whose observed state differs from the one {@link StateSnapshot.Builder#capture()} froze.
 * A getter that observes no state at all, answering null, yields an empty state, which is kept as an observed value
 * rather than treated as "no change", so a state that appears or disappears is reported like any other change. Every
 * component is present: the key, the entity and both states, which are empty rather than null.
 *
 * @param key the machine the entity was tracked under
 * @param entity the tracked entity, kept as the very instance that was tracked
 * @param before the state observed when the snapshot was captured, empty when the getter observed none
 * @param after the state observed when the change was read, empty when the getter observed none
 */
public record StateChange(MachineKey<?, ?, ?> key, Object entity, Optional<Enum<?>> before,
                          Optional<Enum<?>> after) {

    public StateChange {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
    }
}
