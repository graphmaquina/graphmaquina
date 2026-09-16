package io.github.graphmaquina.dsl;

import java.util.List;
import lombok.NonNull;

/** The watch of one state, by names: only the identifiers its rules execute matter structurally. */
public record WatchOutline(@NonNull String state, @NonNull List<String> transitions) {

    public WatchOutline {
        transitions = List.copyOf(transitions);
    }
}
