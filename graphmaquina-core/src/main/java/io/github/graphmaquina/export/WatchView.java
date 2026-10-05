package io.github.graphmaquina.export;

import java.util.List;
import lombok.NonNull;

/**
 * One watch note: the state that declares it, the simple name of the peer machine's entity class, and its rendered
 * rules.
 */
public record WatchView(@NonNull String state, @NonNull String peerMachine, @NonNull List<String> rules) {

    public WatchView {
        rules = List.copyOf(rules);
    }
}
