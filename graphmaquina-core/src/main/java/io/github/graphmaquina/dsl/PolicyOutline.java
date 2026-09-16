package io.github.graphmaquina.dsl;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import lombok.NonNull;

/**
 * The rejection policy of one state, by names: the default reason plus the ones declared per identifier,
 * in the order they were declared. Both producers of an outline establish that order deliberately, so this
 * record keeps it rather than re-copying into an unspecified one.
 */
public record PolicyOutline(@NonNull String state, @NonNull String otherwise, @NonNull Map<String, String> rejections) {

    public PolicyOutline {
        Map<String, String> ordered = new LinkedHashMap<>();
        rejections.forEach((String transition, String reason) -> ordered.put(
                Objects.requireNonNull(transition, "rejected transition"),
                Objects.requireNonNull(reason, "rejection reason")));
        rejections = Collections.unmodifiableMap(ordered);
    }
}
