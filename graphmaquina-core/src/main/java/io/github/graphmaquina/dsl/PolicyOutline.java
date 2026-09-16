package io.github.graphmaquina.dsl;

import java.util.Map;
import lombok.NonNull;

/** The rejection policy of one state, by names: the default reason plus the ones declared per identifier. */
public record PolicyOutline(@NonNull String state, @NonNull String otherwise, @NonNull Map<String, String> rejections) {

    public PolicyOutline {
        rejections = Map.copyOf(rejections);
    }
}
