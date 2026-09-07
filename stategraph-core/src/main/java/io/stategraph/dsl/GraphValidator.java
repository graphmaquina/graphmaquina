package io.stategraph.dsl;

import io.stategraph.api.EdgeSpec;
import io.stategraph.api.MachineKey;
import io.stategraph.api.WatchRule;
import io.stategraph.api.WatchSpec;
import io.stategraph.graph.Graph;
import io.stategraph.graph.GraphEdge;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
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
        List<GraphEdge<S>> arcs = new ArrayList<>();
        for (S from : states) {
            for (S to : states) {
                if (specs.get(new CellKey<>(from, to)).moves()) {
                    arcs.add(new GraphEdge<>(from, to));
                }
            }
        }
        Graph<S> graph = Graph.of(Arrays.asList(states), arcs);

        for (S state : states) {
            List<S> exits = new ArrayList<>();
            for (S to : states) {
                if (to != state && specs.get(new CellKey<>(state, to)).moves()) {
                    exits.add(to);
                }
            }
            boolean terminal = terminals.contains(state);
            if (exits.isEmpty() && !terminal) {
                problems.add("SG202", state.ordinal(), -1,
                        state + " has no ACTION or MOVE exit: declare it with terminal(" + state + ")");
            }
            if (!exits.isEmpty() && terminal) {
                problems.add("SG202", state.ordinal(), -1, state + " is terminal but exits to " + exits);
            }
        }

        S start = initial.get();
        Set<S> reachable = graph.reachableFrom(start);
        for (S state : states) {
            if (state != start && !reachable.contains(state)) {
                problems.add("SG203", state.ordinal(), -1, state + " is unreachable from " + start);
            }
        }

        Set<S> inSomeCycle = EnumSet.noneOf(key.states());
        for (List<S> cycle : graph.cycles()) {
            inSomeCycle.addAll(cycle);
            boolean allowed = false;
            for (S member : cycle) {
                if (cycleAllowed.contains(member)) {
                    allowed = true;
                }
            }
            if (!allowed) {
                List<String> names = new ArrayList<>();
                for (S member : cycle) {
                    names.add(member.name());
                }
                names.add(cycle.get(0).name());
                problems.add("SG204", cycle.get(0).ordinal(), -1, "cycle " + String.join(" → ", names)
                        + "; if intentional, mark one of its states with allowsCycle(...)");
            }
        }
        for (S state : cycleAllowed) {
            if (!inSomeCycle.contains(state)) {
                problems.add("SG205", state.ordinal(), -1,
                        "allowsCycle(" + state + ") is a dead declaration: " + state + " is not in any cycle");
            }
        }

        for (EdgeSpec<E, S> spec : specs.values()) {
            if (spec.onFailure().isEmpty()) {
                continue;
            }
            S failState = spec.onFailure().get();
            String where = spec.from() + " → " + spec.to();
            if (failState == spec.to()) {
                problems.add("SG301", spec.from().ordinal(), spec.to().ordinal(),
                        where + " declares onFailure = " + failState + ", which is its own destination");
                continue;
            }
            EdgeSpec<E, S> route = specs.get(new CellKey<>(spec.from(), failState));
            if (!route.moves()) {
                problems.add("SG301", spec.from().ordinal(), spec.to().ordinal(), where + " declares onFailure = " + failState
                        + ", but the cell " + spec.from() + " → " + failState + " is " + route.kind() + ": it must be ACTION or MOVE");
            }
        }

        for (WatchSpec<E, S, ?, ?> watch : assembly.watches().values()) {
            S state = watch.state();
            for (WatchRule<E, S, ?, ?> rule : watch.rules()) {
                EdgeSpec<E, S> route = specs.get(new CellKey<>(state, rule.target()));
                if (!route.moves()) {
                    problems.add("SG401", state.ordinal(), rule.target().ordinal(), "the watch of " + state
                            + " leads to " + rule.target() + ", but that cell is " + route.kind() + ": it must be ACTION or MOVE");
                }
            }
            if (terminals.contains(state)) {
                problems.add("SG402", state.ordinal(), -1, state + " is terminal and cannot watch anything");
            }
        }
    }
}
