package io.github.graphmaquina.dsl;

import java.util.List;
import lombok.NonNull;

/** The watch of one state, by names: only the targets its rules lead to matter structurally. */
public record WatchOutline(@NonNull String state, @NonNull List<String> targets) {

    public WatchOutline {
        targets = List.copyOf(targets);
    }
}
