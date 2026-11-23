package io.github.graphmaquina.dsl;

import io.github.graphmaquina.api.WatchRule;
import io.github.graphmaquina.api.WatchSpec;
import java.util.ArrayList;
import java.util.List;
import lombok.NonNull;

/** The watch of one state, by names: only the identifiers its rules execute matter structurally. */
public record WatchOutline(@NonNull String state, @NonNull List<String> transitions) {

    public WatchOutline {
        transitions = List.copyOf(transitions);
    }

    /** The outline of {@code watch}: its state, and the identifier each of its rules executes, in order. */
    static WatchOutline of(WatchSpec<?, ?, ?, ?, ?, ?> watch) {
        List<String> executed = new ArrayList<>();
        for (WatchRule<?, ?, ?, ?, ?, ?> rule : watch.rules()) {
            executed.add(rule.transition().name());
        }
        return new WatchOutline(watch.state().name(), executed);
    }
}
