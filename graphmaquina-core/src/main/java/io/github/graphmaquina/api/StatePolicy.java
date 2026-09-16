package io.github.graphmaquina.api;

import java.util.Map;
import lombok.NonNull;

/**
 * Rejection policy of one state: the reason given when a requested transition has no edge from it, plus the
 * reasons declared for specific identifiers. It is not an edge and never becomes one.
 *
 * @param <S> state enum
 * @param <T> transition identifier enum
 * @param state the state this policy describes
 * @param otherwise the reason used for any identifier without its own
 * @param rejectionReasons reasons declared for specific identifiers
 */
public record StatePolicy<S extends Enum<S>, T extends Enum<T>>(@NonNull S state, @NonNull String otherwise,
                                                                @NonNull Map<T, String> rejectionReasons) {

    public StatePolicy {
        rejectionReasons = Map.copyOf(rejectionReasons);
        if (otherwise.isBlank()) {
            throw new IllegalArgumentException("the policy of " + state + " has a blank reason");
        }
    }

    public String reasonFor(T transition) {
        return rejectionReasons.getOrDefault(transition, otherwise);
    }
}
