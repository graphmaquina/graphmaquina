package io.stategraph.dsl;

import io.stategraph.api.EdgeKind;
import io.stategraph.error.InvalidGraphException;
import io.stategraph.graph.Graph;
import io.stategraph.graph.GraphEdge;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.NonNull;

/** SG202, SG203, SG204, SG205, SG301, SG401 and SG402 over a {@link GraphOutline}. Shared by the DSL and the processor. */
public final class GraphOutlineValidator {

    private GraphOutlineValidator() { }

    /** Problems sorted by code, then by the index of the from state, then by the index of the to state. */
    public static List<InvalidGraphException.Problem> validate(@NonNull GraphOutline outline) {
        Problems problems = new Problems();
        validate(outline, problems);
        return problems.sorted();
    }

    static void validate(GraphOutline outline, Problems problems) {
        List<String> states = outline.states();
        List<GraphEdge<String>> arcs = new ArrayList<>();
        for (CellOutline cell : outline.cells()) {
            if (moves(cell)) {
                arcs.add(new GraphEdge<>(cell.from(), cell.to()));
            }
        }
        Graph<String> graph = Graph.of(states, arcs);

        for (String state : states) {
            List<String> exits = new ArrayList<>();
            for (String to : states) {
                if (!to.equals(state) && moves(outline.cell(state, to))) {
                    exits.add(to);
                }
            }
            boolean terminal = outline.terminals().contains(state);
            if (exits.isEmpty() && !terminal) {
                problems.add("SG202", outline.indexOf(state), -1,
                        state + " has no ACTION or MOVE exit: declare it with terminal(" + state + ")");
            }
            if (!exits.isEmpty() && terminal) {
                problems.add("SG202", outline.indexOf(state), -1, state + " is terminal but exits to " + exits);
            }
        }

        String start = outline.initial();
        Set<String> reachable = graph.reachableFrom(start);
        for (String state : states) {
            if (!state.equals(start) && !reachable.contains(state)) {
                problems.add("SG203", outline.indexOf(state), -1, state + " is unreachable from " + start);
            }
        }

        Set<String> inSomeCycle = new HashSet<>();
        for (List<String> cycle : graph.cycles()) {
            inSomeCycle.addAll(cycle);
            boolean allowed = false;
            for (String member : cycle) {
                if (outline.cycleAllowed().contains(member)) {
                    allowed = true;
                }
            }
            if (!allowed) {
                List<String> names = new ArrayList<>(cycle);
                names.add(cycle.get(0));
                problems.add("SG204", outline.indexOf(cycle.get(0)), -1, "cycle " + String.join(" → ", names)
                        + "; if intentional, mark one of its states with allowsCycle(...)");
            }
        }
        for (String state : states) {
            if (outline.cycleAllowed().contains(state) && !inSomeCycle.contains(state)) {
                problems.add("SG205", outline.indexOf(state), -1,
                        "allowsCycle(" + state + ") is a dead declaration: " + state + " is not in any cycle");
            }
        }

        for (CellOutline cell : outline.cells()) {
            if (cell.onFailure().isEmpty()) {
                continue;
            }
            String failState = cell.onFailure().get();
            String where = cell.from() + " → " + cell.to();
            if (failState.equals(cell.to())) {
                problems.add("SG301", outline.indexOf(cell.from()), outline.indexOf(cell.to()),
                        where + " declares onFailure = " + failState + ", which is its own destination");
                continue;
            }
            CellOutline route = outline.cell(cell.from(), failState);
            if (!moves(route)) {
                problems.add("SG301", outline.indexOf(cell.from()), outline.indexOf(cell.to()), where + " declares onFailure = " + failState
                        + ", but the cell " + cell.from() + " → " + failState + " is " + route.kind() + ": it must be ACTION or MOVE");
            }
        }

        for (WatchOutline watch : outline.watches()) {
            String state = watch.state();
            for (String target : watch.targets()) {
                CellOutline route = outline.cell(state, target);
                if (!moves(route)) {
                    problems.add("SG401", outline.indexOf(state), outline.indexOf(target), "the watch of " + state
                            + " leads to " + target + ", but that cell is " + route.kind() + ": it must be ACTION or MOVE");
                }
            }
            if (outline.terminals().contains(state)) {
                problems.add("SG402", outline.indexOf(state), -1, state + " is terminal and cannot watch anything");
            }
        }
    }

    private static boolean moves(CellOutline cell) {
        return cell.kind() == EdgeKind.ACTION || cell.kind() == EdgeKind.MOVE;
    }
}
