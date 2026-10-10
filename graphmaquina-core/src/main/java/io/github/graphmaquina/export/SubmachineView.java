package io.github.graphmaquina.export;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import lombok.NonNull;

/**
 * The sub-machine bound to one state of a {@link GraphView}: the child's own view and the exit of each terminal.
 *
 * @param state the bound state
 * @param child the view of the child's graph, whose {@link GraphView#name() name} names the child machine
 * @param exits the parent identifier that each terminal state of the child selects, by names, in the order given;
 *     a null name is refused
 */
public record SubmachineView(@NonNull String state, @NonNull GraphView child, @NonNull Map<String, String> exits) {

    public SubmachineView {
        Map<String, String> ordered = new LinkedHashMap<>();
        exits.forEach((String terminal, String exit) ->
                ordered.put(Objects.requireNonNull(terminal, "terminal"), Objects.requireNonNull(exit, "exit")));
        exits = Collections.unmodifiableMap(ordered);
    }
}
