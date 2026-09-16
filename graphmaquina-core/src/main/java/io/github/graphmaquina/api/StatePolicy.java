package io.github.graphmaquina.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import lombok.NonNull;

/**
 * Rejection policy of one state: the reason given when a requested transition has no edge from it, plus the
 * reasons declared for specific identifiers. It is not an edge and never becomes one.
 *
 * @param <S> state enum
 * @param <T> transition identifier enum
 * @param state the state this policy describes
 * @param otherwise the reason used for any identifier without its own
 * @param rejectionReasons reasons declared for specific identifiers, in the order they were declared
 */
public record StatePolicy<S extends Enum<S>, T extends Enum<T>>(@NonNull S state, @NonNull String otherwise,
                                                                @NonNull Map<T, String> rejectionReasons) {

    public StatePolicy {
        // Declared order, kept: this map is rendered into a node tooltip, and Map.copyOf iterates in an
        // unspecified order that is salted per JVM, so the same graph would export differently between runs.
        Map<T, String> ordered = new LinkedHashMap<>();
        rejectionReasons.forEach((T transition, String reason) -> ordered.put(
                Objects.requireNonNull(transition, "rejected transition"),
                Objects.requireNonNull(reason, "rejection reason")));
        rejectionReasons = Collections.unmodifiableMap(ordered);
        if (otherwise.isBlank()) {
            throw new IllegalArgumentException("the policy of " + state + " has a blank reason");
        }
    }

    public String reasonFor(T transition) {
        return rejectionReasons.getOrDefault(transition, otherwise);
    }
}
