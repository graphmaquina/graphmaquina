package io.stategraph.processor.model;

import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.TypeElement;

/** Everything the generators need about one @StateGraph interface. */
public record MachineModel(MachineTypes types, String packageName, String initial, List<String> terminals, List<String> cycleAllowed,
                           Property stateGetter, Property stateSetter, Property idGetter, List<RowModel> rows, List<ForbidModel> forbids,
                           List<CellModel> cells, List<OperationModel> operations, List<WatchModel> watches) {

    public MachineModel {
        terminals = List.copyOf(terminals);
        cycleAllowed = List.copyOf(cycleAllowed);
        rows = List.copyOf(rows);
        forbids = List.copyOf(forbids);
        cells = List.copyOf(cells);
        operations = List.copyOf(operations);
        watches = List.copyOf(watches);
    }

    public String facadeName() {
        return types.facade().getSimpleName().toString();
    }

    /** Every ACTION/MOVE cell: internal cells first, then the operations' cells, in declaration order. */
    public List<CellModel> allCells() {
        List<CellModel> all = new ArrayList<>(cells);
        for (OperationModel operation : operations) {
            all.add(operation.cell());
        }
        return all;
    }

    public boolean hasAsync() {
        for (CellModel cell : allCells()) {
            if (cell.async()) {
                return true;
            }
        }
        return false;
    }

    /** by classes with at least one instance member used: internal cells, watches, then operations; first appearance, no repeats. */
    public List<TypeElement> instanceDependencies() {
        List<TypeElement> owners = new ArrayList<>();
        List<Binding> bindings = new ArrayList<>();
        for (CellModel cell : cells) {
            bindings.addAll(cell.bindings());
        }
        for (WatchModel watch : watches) {
            bindings.addAll(watch.bindings());
        }
        for (OperationModel operation : operations) {
            bindings.addAll(operation.cell().bindings());
        }
        for (Binding binding : bindings) {
            if (binding.isStatic()) {
                continue;
            }
            boolean known = false;
            for (TypeElement owner : owners) {
                if (owner.getQualifiedName().contentEquals(binding.owner().getQualifiedName())) {
                    known = true;
                }
            }
            if (!known) {
                owners.add(binding.owner());
            }
        }
        return owners;
    }
}
