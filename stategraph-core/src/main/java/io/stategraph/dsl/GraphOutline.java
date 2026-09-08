package io.stategraph.dsl;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.NonNull;

/**
 * Structural model of a graph by names only: the full N×N matrix with its kinds and failure routes, plus the
 * watches. The DSL builds it from its assembly and the annotation processor from the annotations, so the same
 * validator runs in {@code build()} and in javac. {@code states} are in declaration order.
 */
public record GraphOutline(@NonNull String name, @NonNull List<String> states, @NonNull String initial,
                           @NonNull Set<String> terminals, @NonNull Set<String> cycleAllowed,
                           @NonNull List<CellOutline> cells, @NonNull List<WatchOutline> watches) {

    public GraphOutline {
        states = List.copyOf(states);
        terminals = Set.copyOf(terminals);
        cycleAllowed = Set.copyOf(cycleAllowed);
        cells = List.copyOf(cells);
        watches = List.copyOf(watches);
        if (states.isEmpty() || new HashSet<>(states).size() != states.size()) {
            throw new IllegalArgumentException(name + ": states must be non-empty and distinct");
        }
        requireKnown(name, states, initial, "initial");
        for (String terminal : terminals) {
            requireKnown(name, states, terminal, "terminal");
        }
        for (String state : cycleAllowed) {
            requireKnown(name, states, state, "allowsCycle");
        }
        int n = states.size();
        if (cells.size() != n * n) {
            throw new IllegalArgumentException(name + ": expected " + (n * n) + " cells, got " + cells.size());
        }
        Set<String> seen = new HashSet<>();
        for (CellOutline cell : cells) {
            requireKnown(name, states, cell.from(), "cell.from");
            requireKnown(name, states, cell.to(), "cell.to");
            if (cell.onFailure().isPresent()) {
                requireKnown(name, states, cell.onFailure().get(), "cell.onFailure");
            }
            if (!seen.add(cell.from() + " → " + cell.to())) {
                throw new IllegalArgumentException(name + ": the cell " + cell.from() + " → " + cell.to() + " appears twice");
            }
        }
        for (WatchOutline watch : watches) {
            requireKnown(name, states, watch.state(), "watch.state");
            for (String target : watch.targets()) {
                requireKnown(name, states, target, "watch.target");
            }
        }
    }

    private static void requireKnown(String name, List<String> states, String state, String role) {
        if (!states.contains(state)) {
            throw new IllegalArgumentException(name + ": " + role + " " + state + " is not one of " + states);
        }
    }

    /** The cell (from, to); the matrix is complete by construction. */
    public CellOutline cell(String from, String to) {
        requireKnown(name, states, from, "cell.from");
        requireKnown(name, states, to, "cell.to");
        int n = states.size();
        int fromIndex = states.indexOf(from);
        int toIndex = states.indexOf(to);
        CellOutline direct = cells.get(fromIndex * n + toIndex);
        if (direct.from().equals(from) && direct.to().equals(to)) {
            return direct;
        }
        for (CellOutline cell : cells) {
            if (cell.from().equals(from) && cell.to().equals(to)) {
                return cell;
            }
        }
        throw new IllegalStateException("unreachable: the matrix is complete");
    }

    public int indexOf(String state) {
        return states.indexOf(state);
    }
}
