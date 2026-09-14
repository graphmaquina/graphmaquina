package io.github.graphmaquina.test;

import io.github.graphmaquina.api.MachineKey;
import java.util.Objects;

/**
 * One tracked entity whose observed state differs from the one {@link StateSnapshot.Builder#capture()} froze.
 * A getter that observes no state at all yields null, which is kept as an observed value rather than
 * treated as "no change", so a state that appears or disappears is reported like any other change; the
 * key and the entity are always present.
 *
 * @param key the machine the entity was tracked under
 * @param entity the tracked entity, kept as the very instance that was tracked
 * @param before the state observed when the snapshot was captured, or null
 * @param after the state observed when the change was read, or null
 */
public record StateChange(MachineKey<?, ?> key, Object entity, Enum<?> before, Enum<?> after) {

    public StateChange {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(entity, "entity");
    }
}
