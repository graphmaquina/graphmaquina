package io.stategraph.processor;

import io.stategraph.annotation.Row;
import io.stategraph.annotation.Rows;
import io.stategraph.annotation.StateGraph;
import io.stategraph.processor.model.CellModel;
import io.stategraph.processor.model.ForbidModel;
import io.stategraph.processor.model.MachineModel;
import io.stategraph.processor.model.MachineTypes;
import io.stategraph.processor.model.OperationModel;
import io.stategraph.processor.model.Property;
import io.stategraph.processor.model.RowModel;
import io.stategraph.processor.model.WatchModel;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import lombok.RequiredArgsConstructor;

/** Turns a @StateGraph interface into a {@link MachineModel}, reporting every problem it finds with an SG code. */
@RequiredArgsConstructor
final class MachineModelBuilder {

    static final String MACHINE = "io.stategraph.api.Machine";

    private final Annotations annotations;
    private final Signatures signatures;
    private final Accessors accessors;
    private final Diagnostics diagnostics;

    /** Empty when the interface has errors; nothing is generated for it then. */
    Optional<MachineModel> build(TypeElement type) {
        int before = diagnostics.errors();
        AnnotationMirror graph = annotations.find(type, StateGraph.class).orElseThrow();
        Optional<MachineTypes> resolved = machineTypes(type);
        if (resolved.isEmpty()) {
            diagnostics.error("SG502", type.getSimpleName() + " must be an interface extending Machine<E, S> with a concrete entity"
                    + " type and an enum state type", type, graph);
            return Optional.empty();
        }
        MachineTypes types = resolved.get();
        String initial = annotations.string(graph, "initial");
        checkState(types, initial, type, graph, annotations.value(graph, "initial"));
        List<String> terminals = checkStates(types, graph, "terminals", type);
        List<String> cycleAllowed = checkStates(types, graph, "allowsCycle", type);

        Optional<Property> stateGetter = Optional.empty();
        Optional<Property> stateSetter = Optional.empty();
        String stateProperty = annotations.string(graph, "state");
        Optional<ExecutableElement> getter = accessors.getter(types.entityElement(), stateProperty);
        if (getter.isPresent() && signatures.same(getter.get().getReturnType(), types.states())) {
            stateGetter = Optional.of(new Property(getter.get().getSimpleName().toString()));
        } else {
            diagnostics.error("SG503", types.entityName() + " has no public state getter for property '" + stateProperty + "': expected "
                    + stateProperty + "() or get" + Accessors.capitalize(stateProperty) + "() returning " + types.statesName(),
                    type, graph, annotations.value(graph, "state"));
        }
        Optional<ExecutableElement> setter = accessors.setter(types.entityElement(), stateProperty, types.states());
        if (setter.isPresent()) {
            stateSetter = Optional.of(new Property(setter.get().getSimpleName().toString()));
        } else {
            diagnostics.error("SG503", types.entityName() + " has no public state setter for property '" + stateProperty + "': expected set"
                    + Accessors.capitalize(stateProperty) + "(" + types.statesName() + ") or " + stateProperty + "(" + types.statesName() + ")",
                    type, graph, annotations.value(graph, "state"));
        }
        String idProperty = annotations.string(graph, "id");
        Optional<ExecutableElement> id = accessors.getter(types.entityElement(), idProperty);
        Optional<Property> idGetter = id.map((ExecutableElement method) -> new Property(method.getSimpleName().toString()));
        if (idGetter.isEmpty()) {
            diagnostics.error("SG503", types.entityName() + " has no public identity getter for property '" + idProperty + "': expected "
                    + idProperty + "() or get" + Accessors.capitalize(idProperty) + "()", type, graph, annotations.value(graph, "id"));
        }

        List<RowModel> rows = readRows(type, types);
        List<ForbidModel> forbids = new ArrayList<>();      // Task 8
        List<CellModel> cells = new ArrayList<>();          // Task 8
        List<OperationModel> operations = new ArrayList<>(); // Task 8
        List<WatchModel> watches = new ArrayList<>();       // Task 9

        for (String state : types.stateNames()) {
            boolean closed = false;
            for (RowModel row : rows) {
                if (row.state().equals(state)) {
                    closed = true;
                }
            }
            if (!closed && !terminals.contains(state)) {
                diagnostics.error("SG506", state + " is not terminal and has no @Row: add @Row(state = \"" + state
                        + "\", otherwise = \"why its other cells are forbidden\")", type, graph);
            }
        }

        if (diagnostics.errors() > before) {
            return Optional.empty();
        }
        String packageName = signatures.elements().getPackageOf(type).getQualifiedName().toString();
        return Optional.of(new MachineModel(types, packageName, initial, terminals, cycleAllowed, stateGetter.orElseThrow(),
                stateSetter.orElseThrow(), idGetter.orElseThrow(), rows, forbids, cells, operations, watches));
    }

    /** E and S from {@code extends Machine<E, S>}, searched through the supertypes; empty when not an interface or not resolvable. */
    Optional<MachineTypes> machineTypes(TypeElement type) {
        if (type.getKind() != ElementKind.INTERFACE) {
            return Optional.empty();
        }
        Optional<DeclaredType> machine = findMachine(type.asType());
        if (machine.isEmpty() || machine.get().getTypeArguments().size() != 2) {
            return Optional.empty();
        }
        TypeMirror entity = machine.get().getTypeArguments().get(0);
        TypeMirror states = machine.get().getTypeArguments().get(1);
        if (!(entity instanceof DeclaredType entityType) || !(states instanceof DeclaredType statesType) || !signatures.isEnum(states)) {
            return Optional.empty();
        }
        TypeElement statesElement = (TypeElement) statesType.asElement();
        List<String> names = new ArrayList<>();
        for (Element member : statesElement.getEnclosedElements()) {
            if (member.getKind() == ElementKind.ENUM_CONSTANT) {
                names.add(member.getSimpleName().toString());
            }
        }
        return Optional.of(new MachineTypes(type, entityType, (TypeElement) entityType.asElement(), statesType, statesElement, names));
    }

    private Optional<DeclaredType> findMachine(TypeMirror type) {
        for (TypeMirror supertype : signatures.types().directSupertypes(type)) {
            if (supertype instanceof DeclaredType declared) {
                TypeElement element = (TypeElement) declared.asElement();
                if (element.getQualifiedName().contentEquals(MACHINE)) {
                    return Optional.of(declared);
                }
                Optional<DeclaredType> deeper = findMachine(declared);
                if (deeper.isPresent()) {
                    return deeper;
                }
            }
        }
        return Optional.empty();
    }

    private List<RowModel> readRows(TypeElement type, MachineTypes types) {
        List<RowModel> rows = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (AnnotationMirror row : annotations.findAll(type, Row.class, Rows.class)) {
            String state = annotations.string(row, "state");
            String otherwise = annotations.string(row, "otherwise");
            if (!checkState(types, state, type, row, annotations.value(row, "state"))) {
                continue;
            }
            if (!seen.add(state)) {
                diagnostics.error("SG102", "the row " + state + " is closed twice", type, row, annotations.value(row, "state"));
                continue;
            }
            if (otherwise.isBlank()) {
                diagnostics.error("SG103", "the row " + state + " has a blank reason", type, row, annotations.value(row, "otherwise"));
                continue;
            }
            rows.add(new RowModel(state, otherwise));
        }
        return rows;
    }

    /** SG501 with a suggestion; true when the name is a constant of the machine's enum. */
    boolean checkState(MachineTypes types, String state, Element element, AnnotationMirror mirror, AnnotationValue value) {
        if (types.hasState(state)) {
            return true;
        }
        diagnostics.error("SG501", Suggestions.describeUnknownState(state, types.statesName(), types.stateNames()), element, mirror, value);
        return false;
    }

    /** Every valid name of a String[] element, reporting SG501 for the others. */
    List<String> checkStates(MachineTypes types, AnnotationMirror mirror, String name, Element element) {
        List<String> valid = new ArrayList<>();
        for (AnnotationValue value : annotations.array(mirror, name)) {
            String state = (String) value.getValue();
            if (checkState(types, state, element, mirror, value)) {
                valid.add(state);
            }
        }
        return valid;
    }
}
