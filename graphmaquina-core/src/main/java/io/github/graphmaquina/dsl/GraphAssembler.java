package io.github.graphmaquina.dsl;

import io.github.graphmaquina.api.EdgeKind;
import io.github.graphmaquina.api.EdgeSpec;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.WatchSpec;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Turns row and cell drafts into an {@link Assembly}, filling forbidden rows and reporting missing cells. */
final class GraphAssembler {

    private static final int MAX_LISTED_MISSING = 40;

    private GraphAssembler() { }

    static <E, S extends Enum<S>> Assembly<E, S> assemble(MachineKey<E, S> key, S[] states, int initialDeclarations, Set<S> terminals,
                                                          Map<CellKey<S>, EdgeDraft<E, S>> cells,
                                                          Map<S, String> rowForbids,
                                                          Map<S, WatchDraft<E, S, ?, ?>> watches,
                                                          Problems problems) {
        if (initialDeclarations == 0) {
            problems.add("SG201", "the graph " + key + " does not declare initial(...)");
        } else if (initialDeclarations > 1) {
            problems.add("SG201", "initial(...) was declared " + initialDeclarations + " times in " + key);
        }
        Map<CellKey<S>, EdgeSpec<E, S>> specs = new LinkedHashMap<>();
        Set<CellKey<S>> declared = new HashSet<>();
        for (Map.Entry<CellKey<S>, EdgeDraft<E, S>> entry : cells.entrySet()) {
            if (entry.getValue().kind().isEmpty()) {
                continue;
            }
            declared.add(entry.getKey());
            Optional<EdgeSpec<E, S>> spec = entry.getValue().toSpec(problems, terminals);
            if (spec.isPresent()) {
                specs.put(entry.getKey(), spec.get());
            }
        }
        List<CellKey<S>> missing = new ArrayList<>();
        for (S from : states) {
            for (S to : states) {
                CellKey<S> cell = new CellKey<>(from, to);
                if (declared.contains(cell)) {
                    continue;
                }
                String rowReason = rowForbids.get(from);
                if (rowReason == null) {
                    missing.add(cell);
                    continue;
                }
                declared.add(cell);
                if (!rowReason.isBlank()) {
                    specs.put(cell, new EdgeSpec<>(from, to, EdgeKind.FORBID, rowReason, Optional.empty(), List.of(),
                            Optional.empty(), List.of(), Optional.empty()));
                }
            }
        }
        if (!missing.isEmpty()) {
            problems.add("SG101", missingMessage(key, states.length, missing));
        }
        Map<S, WatchSpec<E, S, ?, ?>> watchSpecs = new LinkedHashMap<>();
        for (Map.Entry<S, WatchDraft<E, S, ?, ?>> entry : watches.entrySet()) {
            Optional<? extends WatchSpec<E, S, ?, ?>> spec = entry.getValue().toSpec(problems);
            if (spec.isPresent()) {
                watchSpecs.put(entry.getKey(), spec.get());
            }
        }
        boolean complete = missing.isEmpty() && specs.size() == states.length * states.length;
        return new Assembly<>(specs, watchSpecs, complete);
    }

    private static <S extends Enum<S>> String missingMessage(MachineKey<?, ?> key, int n, List<CellKey<S>> missing) {
        StringBuilder text = new StringBuilder("the matrix of " + key.states().getSimpleName() + " is incomplete: missing "
                + missing.size() + " of " + (n * n) + " cells. Paste this and decide each kind:");
        int listed = 0;
        int lastRow = -1;
        for (CellKey<S> cell : missing) {
            if (listed == MAX_LISTED_MISSING) {
                break;
            }
            if (cell.from().ordinal() != lastRow) {
                text.append("\n    .from(").append(cell.from().name()).append(')');
                lastRow = cell.from().ordinal();
            }
            text.append("\n        .to(").append(cell.to().name()).append(").forbid(\"TODO why not\")");
            listed++;
        }
        if (missing.size() > listed) {
            text.append("\n    ... and ").append(missing.size() - listed).append(" more cells");
        }
        return text.toString();
    }
}
