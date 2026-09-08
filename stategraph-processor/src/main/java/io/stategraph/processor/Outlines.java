package io.stategraph.processor;

import io.stategraph.api.EdgeKind;
import io.stategraph.dsl.CellOutline;
import io.stategraph.dsl.GraphOutline;
import io.stategraph.dsl.WatchOutline;
import io.stategraph.processor.model.CellModel;
import io.stategraph.processor.model.ForbidModel;
import io.stategraph.processor.model.MachineModel;
import io.stategraph.processor.model.RuleModel;
import io.stategraph.processor.model.WatchModel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Turns a model into the full N×N outline: declared cells, STAY on the diagonal, the row's FORBID everywhere else. */
final class Outlines {

    private Outlines() { }

    static GraphOutline of(MachineModel model) {
        List<String> states = model.types().stateNames();
        Map<String, CellOutline> declared = new HashMap<>();
        for (ForbidModel forbid : model.forbids()) {
            declared.put(forbid.from() + ">" + forbid.to(), new CellOutline(forbid.from(), forbid.to(), EdgeKind.FORBID, Optional.empty()));
        }
        for (CellModel cell : model.allCells()) {
            for (String from : cell.from()) {
                declared.put(from + ">" + cell.to(), new CellOutline(from, cell.to(), cell.kind(), cell.onFailure()));
            }
        }
        List<CellOutline> cells = new ArrayList<>();
        for (String from : states) {
            for (String to : states) {
                if (from.equals(to)) {
                    cells.add(new CellOutline(from, to, EdgeKind.STAY, Optional.empty()));
                } else {
                    cells.add(declared.getOrDefault(from + ">" + to, new CellOutline(from, to, EdgeKind.FORBID, Optional.empty())));
                }
            }
        }
        List<WatchOutline> watches = new ArrayList<>();
        for (WatchModel watch : model.watches()) {
            List<String> targets = new ArrayList<>();
            for (RuleModel rule : watch.rules()) {
                targets.add(rule.goTo());
            }
            watches.add(new WatchOutline(watch.state(), targets));
        }
        return new GraphOutline(model.facadeName(), states, model.initial(), Set.copyOf(model.terminals()), Set.copyOf(model.cycleAllowed()),
                cells, watches);
    }
}
