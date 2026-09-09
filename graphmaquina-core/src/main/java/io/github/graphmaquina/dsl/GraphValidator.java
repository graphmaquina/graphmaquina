package io.github.graphmaquina.dsl;

import io.github.graphmaquina.api.EdgeSpec;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.WatchRule;
import io.github.graphmaquina.api.WatchSpec;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Structural checks over a complete matrix: terminals, reachability, cycles, failure routes and watches. */
final class GraphValidator {

    private GraphValidator() { }

    /** Validates the structure of the graph; adds nothing when the matrix is incomplete or initial is undeclared. */
    static <E, S extends Enum<S>> void validate(MachineKey<E, S> key, S[] states, Optional<S> initial, Set<S> terminals,
                                                Set<S> cycleAllowed, Assembly<E, S> assembly, Problems problems) {
        if (!assembly.complete() || initial.isEmpty()) {
            return;
        }
        Map<CellKey<S>, EdgeSpec<E, S>> specs = assembly.specs();
        List<String> names = new ArrayList<>();
        for (S state : states) {
            names.add(state.name());
        }
        List<CellOutline> cells = new ArrayList<>();
        for (S from : states) {
            for (S to : states) {
                EdgeSpec<E, S> spec = specs.get(new CellKey<>(from, to));
                cells.add(new CellOutline(from.name(), to.name(), spec.kind(), spec.onFailure().map(Enum::name)));
            }
        }
        List<WatchOutline> watches = new ArrayList<>();
        for (WatchSpec<E, S, ?, ?> watch : assembly.watches().values()) {
            List<String> targets = new ArrayList<>();
            for (WatchRule<E, S, ?, ?> rule : watch.rules()) {
                targets.add(rule.target().name());
            }
            watches.add(new WatchOutline(watch.state().name(), targets));
        }
        Set<String> terminalNames = new LinkedHashSet<>();
        for (S state : terminals) {
            terminalNames.add(state.name());
        }
        Set<String> cycleNames = new LinkedHashSet<>();
        for (S state : cycleAllowed) {
            cycleNames.add(state.name());
        }
        GraphOutline outline = new GraphOutline(key.toString(), names, initial.get().name(), terminalNames, cycleNames, cells, watches);
        GraphOutlineValidator.validate(outline, problems);
    }
}
