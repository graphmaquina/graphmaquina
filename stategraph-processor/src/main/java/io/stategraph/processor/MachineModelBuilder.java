package io.stategraph.processor;

import io.stategraph.annotation.Action;
import io.stategraph.annotation.Cell;
import io.stategraph.annotation.Cells;
import io.stategraph.annotation.Forbid;
import io.stategraph.annotation.Forbids;
import io.stategraph.annotation.Move;
import io.stategraph.annotation.Row;
import io.stategraph.annotation.Rows;
import io.stategraph.annotation.StateGraph;
import io.stategraph.annotation.Watch;
import io.stategraph.annotation.Watches;
import io.stategraph.api.EdgeKind;
import io.stategraph.dsl.GraphOutlineValidator;
import io.stategraph.error.InvalidGraphException;
import io.stategraph.processor.model.Binding;
import io.stategraph.processor.model.CellModel;
import io.stategraph.processor.model.ForbidModel;
import io.stategraph.processor.model.MachineModel;
import io.stategraph.processor.model.MachineTypes;
import io.stategraph.processor.model.OperationModel;
import io.stategraph.processor.model.Property;
import io.stategraph.processor.model.RowModel;
import io.stategraph.processor.model.RuleKind;
import io.stategraph.processor.model.RuleModel;
import io.stategraph.processor.model.WatchModel;
import java.time.Duration;
import java.time.format.DateTimeParseException;
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
import javax.lang.model.element.Modifier;
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
    private final Bindings bindings;
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

        Set<String> closed = new LinkedHashSet<>();
        List<RowModel> rows = readRows(type, types, closed);
        List<ForbidModel> forbids = readForbids(type, types);
        List<CellModel> cells = readCells(type, types);
        List<OperationModel> operations = readOperations(type, types);
        checkDuplicateCells(type, forbids, cells, operations);
        List<WatchModel> watches = readWatches(type, types);

        for (String state : types.stateNames()) {
            if (!closed.contains(state) && !terminals.contains(state)) {
                diagnostics.error("SG506", state + " is not terminal and has no @Row: add @Row(state = \"" + state
                        + "\", otherwise = \"why its other cells are forbidden\")", type, graph);
            }
        }

        if (diagnostics.errors() > before) {
            return Optional.empty();
        }
        String packageName = signatures.elements().getPackageOf(type).getQualifiedName().toString();
        MachineModel model = new MachineModel(types, packageName, initial, terminals, cycleAllowed, stateGetter.orElseThrow(),
                stateSetter.orElseThrow(), idGetter.orElseThrow(), rows, forbids, cells, operations, watches);
        for (InvalidGraphException.Problem problem : GraphOutlineValidator.validate(Outlines.of(model))) {
            diagnostics.error(problem.code(), problem.message(), type, graph);
        }
        if (diagnostics.errors() > before) {
            return Optional.empty();
        }
        return Optional.of(model);
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

    /** {@code seen} collects every state that carries a @Row, blank reason included, so SG103 does not also produce SG506. */
    private List<RowModel> readRows(TypeElement type, MachineTypes types, Set<String> seen) {
        List<RowModel> rows = new ArrayList<>();
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

    private List<ForbidModel> readForbids(TypeElement type, MachineTypes types) {
        List<ForbidModel> forbids = new ArrayList<>();
        for (AnnotationMirror forbid : annotations.findAll(type, Forbid.class, Forbids.class)) {
            String from = annotations.string(forbid, "from");
            String to = annotations.string(forbid, "to");
            String reason = annotations.string(forbid, "reason");
            boolean valid = checkState(types, from, type, forbid, annotations.value(forbid, "from"))
                    & checkState(types, to, type, forbid, annotations.value(forbid, "to"));
            if (reason.isBlank()) {
                diagnostics.error("SG103", "the @Forbid " + from + " → " + to + " has a blank reason", type, forbid, annotations.value(forbid, "reason"));
                valid = false;
            }
            if (valid) {
                forbids.add(new ForbidModel(from, to, reason));
            }
        }
        return forbids;
    }

    private List<CellModel> readCells(TypeElement type, MachineTypes types) {
        List<CellModel> cells = new ArrayList<>();
        for (AnnotationMirror mirror : annotations.findAll(type, Cell.class, Cells.class)) {
            cell(type, mirror, types, "", false).ifPresent(cells::add);
        }
        return cells;
    }

    private List<OperationModel> readOperations(TypeElement type, MachineTypes types) {
        List<OperationModel> operations = new ArrayList<>();
        DeclaredType outcome = signatures.declared("io.stategraph.api.Outcome", types.entity(), types.states());
        for (Element member : type.getEnclosedElements()) {
            if (member.getKind() != ElementKind.METHOD) {
                continue;
            }
            ExecutableElement method = (ExecutableElement) member;
            if (method.isDefault() || method.getModifiers().contains(Modifier.STATIC) || method.getModifiers().contains(Modifier.PRIVATE)) {
                continue;
            }
            Optional<AnnotationMirror> action = annotations.find(method, Action.class);
            Optional<AnnotationMirror> move = annotations.find(method, Move.class);
            if (action.isPresent() == move.isPresent()) {
                diagnostics.error("SG504", method.getSimpleName() + " must carry exactly one of @Action or @Move", method);
                continue;
            }
            boolean signatureOk = method.getParameters().size() == 1
                    && signatures.same(method.getParameters().get(0).asType(), types.entity())
                    && signatures.same(method.getReturnType(), outcome);
            if (!signatureOk) {
                diagnostics.error("SG504", "an operation is declared as Outcome<" + types.entityName() + ", " + types.statesName() + "> "
                        + method.getSimpleName() + "(" + types.entityName() + ")", method);
                continue;
            }
            String name = method.getSimpleName().toString();
            AnnotationMirror mirror = action.isPresent() ? action.get() : move.get();
            cell(method, mirror, types, name, move.isPresent()).ifPresent((CellModel cell) -> operations.add(new OperationModel(name, cell)));
        }
        return operations;
    }

    private List<WatchModel> readWatches(TypeElement type, MachineTypes types) {
        List<WatchModel> watches = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (AnnotationMirror watch : annotations.findAll(type, Watch.class, Watches.class)) {
            int before = diagnostics.errors();
            String state = annotations.string(watch, "state");
            if (checkState(types, state, type, watch, annotations.value(watch, "state")) && !seen.add(state)) {
                diagnostics.error("SG403", "the state " + state + " declares two watches", type, watch, annotations.value(watch, "state"));
            }
            TypeMirror peerType = annotations.type(watch, "peer");
            Optional<MachineTypes> peer = Optional.empty();
            if (peerType instanceof DeclaredType declared && annotations.find(declared.asElement(), StateGraph.class).isPresent()) {
                peer = machineTypes((TypeElement) declared.asElement());
            }
            if (peer.isEmpty()) {
                diagnostics.error("SG509", "peer must be a @StateGraph interface, not " + peerType, type, watch, annotations.value(watch, "peer"));
                continue;
            }
            String peersName = annotations.string(watch, "peers");
            Optional<ExecutableElement> peersGetter = accessors.getter(types.entityElement(), peersName);
            TypeMirror collectionOfPeers = signatures.declared("java.util.Collection", signatures.extendsWildcard(peer.get().entity()));
            Optional<Property> peers = Optional.empty();
            if (peersGetter.isPresent() && signatures.assignable(peersGetter.get().getReturnType(), collectionOfPeers)) {
                peers = Optional.of(new Property(peersGetter.get().getSimpleName().toString()));
            } else {
                diagnostics.error("SG503", types.entityName() + " has no public getter for property '" + peersName + "' returning Collection<? extends "
                        + peer.get().entityName() + ">", type, watch, annotations.value(watch, "peers"));
            }
            String ownerName = annotations.string(watch, "owner");
            Optional<ExecutableElement> ownerGetter = accessors.getter(peer.get().entityElement(), ownerName);
            Optional<Property> owner = Optional.empty();
            boolean ownerIsOptional = false;
            if (ownerGetter.isPresent()) {
                TypeMirror returned = ownerGetter.get().getReturnType();
                if (signatures.same(returned, signatures.declared("java.util.Optional", types.entity()))) {
                    owner = Optional.of(new Property(ownerGetter.get().getSimpleName().toString()));
                    ownerIsOptional = true;
                } else if (signatures.assignable(returned, types.entity())) {
                    owner = Optional.of(new Property(ownerGetter.get().getSimpleName().toString()));
                }
            }
            if (owner.isEmpty()) {
                diagnostics.error("SG503", peer.get().entityName() + " has no public getter for property '" + ownerName + "' returning Optional<"
                        + types.entityName() + "> or " + types.entityName(), type, watch, annotations.value(watch, "owner"));
            }
            TypeMirror by = annotations.type(watch, "by");
            List<AnnotationMirror> ruleMirrors = annotations.annotations(watch, "rules");
            if (ruleMirrors.isEmpty()) {
                diagnostics.error("SG404", "the watch of " + state + " declares no rule", type, watch, annotations.value(watch, "rules"));
            }
            List<RuleModel> rules = new ArrayList<>();
            for (AnnotationMirror rule : ruleMirrors) {
                rule(rule, type, types, peer.get(), by).ifPresent(rules::add);
            }
            if (diagnostics.errors() == before) {
                watches.add(new WatchModel(state, peer.get(), peers.orElseThrow(), owner.orElseThrow(), ownerIsOptional, rules));
            }
        }
        return watches;
    }

    private Optional<RuleModel> rule(AnnotationMirror rule, TypeElement type, MachineTypes types, MachineTypes peer, TypeMirror by) {
        int before = diagnostics.errors();
        String condition = annotations.string(rule, "condition");
        String allIn = annotations.string(rule, "allIn");
        String anyIn = annotations.string(rule, "anyIn");
        String noneIn = annotations.string(rule, "noneIn");
        int given = 0;
        for (String value : new String[] {condition, allIn, anyIn, noneIn}) {
            if (!value.isEmpty()) {
                given++;
            }
        }
        if (given != 1) {
            diagnostics.error("SG508", "@When needs exactly one of condition, allIn, anyIn, noneIn", type, rule);
        }
        String goTo = annotations.string(rule, "goTo");
        checkState(types, goTo, type, rule, annotations.value(rule, "goTo"));
        RuleKind kind = RuleKind.CONDITION;
        String argument = "";
        Optional<Binding> binding = Optional.empty();
        if (!condition.isEmpty()) {
            if (annotations.isVoid(by)) {
                diagnostics.error("SG508", "a condition needs @Watch.by, the class hosting it", type, rule, annotations.value(rule, "condition"));
            } else {
                binding = bindings.resolve(by, condition, Bindings.Role.CONDITION, types, Optional.of(peer), type, rule,
                        annotations.value(rule, "condition"));
            }
        } else if (!allIn.isEmpty()) {
            kind = RuleKind.ALL_IN;
            argument = allIn;
            checkState(peer, allIn, type, rule, annotations.value(rule, "allIn"));
        } else if (!anyIn.isEmpty()) {
            kind = RuleKind.ANY_IN;
            argument = anyIn;
            checkState(peer, anyIn, type, rule, annotations.value(rule, "anyIn"));
        } else if (!noneIn.isEmpty()) {
            kind = RuleKind.NONE_IN;
            argument = noneIn;
            checkState(peer, noneIn, type, rule, annotations.value(rule, "noneIn"));
        }
        if (diagnostics.errors() > before) {
            return Optional.empty();
        }
        return Optional.of(new RuleModel(kind, argument, binding, goTo));
    }

    /**
     * One @Cell, @Action or @Move. {@code operation} is the method name for operations and empty for @Cell;
     * {@code isMove} distinguishes @Move (no action, onFailure, failsOn, timeout elements) from the other two.
     */
    private Optional<CellModel> cell(Element element, AnnotationMirror mirror, MachineTypes types, String operation, boolean isMove) {
        int before = diagnostics.errors();
        List<String> from = checkStates(types, mirror, "from", element);
        if (annotations.array(mirror, "from").isEmpty()) {
            diagnostics.error("SG507", "from must name at least one state", element, mirror, annotations.value(mirror, "from"));
        }
        String to = annotations.string(mirror, "to");
        checkState(types, to, element, mirror, annotations.value(mirror, "to"));
        if (from.contains(to)) {
            diagnostics.error("SG104", to + " is both in from and to: a cell cannot move to itself; the diagonal is always STAY",
                    element, mirror, annotations.value(mirror, "to"));
        }
        TypeMirror by = annotations.type(mirror, "by");
        boolean hasBy = !annotations.isVoid(by);
        List<String> guardNames = annotations.strings(mirror, "guards");
        String afterCommit = annotations.string(mirror, "afterCommit");

        String actionName = "";
        String actionMethod = "";
        TypeMirror guardsBy = by;
        Optional<String> onFailure = Optional.empty();
        List<TypeMirror> failsOn = List.of();
        Optional<Duration> timeout = Optional.empty();
        if (!isMove) {
            String action = annotations.string(mirror, "action");
            if (operation.isEmpty()) {
                if (hasBy && action.isEmpty()) {
                    diagnostics.error("SG507", "a @Cell with by must name its action", element, mirror, annotations.value(mirror, "by"));
                }
                if (!hasBy && !action.isEmpty()) {
                    diagnostics.error("SG507", "a @Cell with an action needs by", element, mirror, annotations.value(mirror, "action"));
                }
                actionName = action;
                actionMethod = action;
            } else {
                actionName = operation;
                actionMethod = action.isEmpty() ? operation : action;
            }
            TypeMirror declaredGuardsBy = annotations.type(mirror, "guardsBy");
            if (!annotations.isVoid(declaredGuardsBy)) {
                guardsBy = declaredGuardsBy;
                if (guardNames.isEmpty()) {
                    diagnostics.error("SG507", "guardsBy without guards", element, mirror, annotations.value(mirror, "guardsBy"));
                }
            }
            String onFailureName = annotations.string(mirror, "onFailure");
            if (!onFailureName.isEmpty()) {
                if (checkState(types, onFailureName, element, mirror, annotations.value(mirror, "onFailure"))) {
                    onFailure = Optional.of(onFailureName);
                }
            }
            failsOn = annotations.types(mirror, "failsOn");
            List<AnnotationValue> failsOnValues = annotations.array(mirror, "failsOn");
            for (int i = 0; i < failsOn.size(); i++) {
                if (isForbiddenFailsOn(failsOn.get(i))) {
                    diagnostics.error("SG302", "failsOn does not allow " + failsOn.get(i) + "; only exceptions foreign to the library, and never"
                            + " Throwable, Exception, RuntimeException or an Error", element, mirror, failsOnValues.get(i));
                }
            }
            String timeoutText = annotations.string(mirror, "timeout");
            if (!timeoutText.isEmpty()) {
                timeout = parseTimeout(timeoutText, element, mirror, annotations.value(mirror, "timeout"));
            }
            boolean actionCell = hasBy && !actionName.isEmpty();
            if (!actionCell && (onFailure.isPresent() || !failsOn.isEmpty() || timeout.isPresent())) {
                diagnostics.error("SG507", "onFailure, failsOn and timeout need an action: add by and action", element, mirror,
                        annotations.value(mirror, "to"));
            }
        }
        boolean hasGuardHost = !annotations.isVoid(guardsBy);
        if (!guardNames.isEmpty() && !hasGuardHost) {
            diagnostics.error("SG507", "guards need by" + (isMove ? "" : " or guardsBy"), element, mirror, annotations.value(mirror, "guards"));
        }
        if (!afterCommit.isEmpty() && !hasBy) {
            diagnostics.error("SG507", "afterCommit needs by", element, mirror, annotations.value(mirror, "afterCommit"));
        }

        // Bindings are resolved even when a state name was wrong, so every problem of the cell is reported at once.
        boolean action = !isMove && hasBy && !actionName.isEmpty();
        Optional<Binding> actionBinding = Optional.empty();
        if (action) {
            AnnotationValue anchor = annotations.string(mirror, "action").isEmpty() ? annotations.value(mirror, "by") : annotations.value(mirror, "action");
            actionBinding = bindings.resolve(by, actionMethod, Bindings.Role.ACTION, types, Optional.empty(), element, mirror, anchor);
        }
        List<Binding> guards = new ArrayList<>();
        Set<String> seenGuards = new LinkedHashSet<>();
        List<AnnotationValue> guardValues = annotations.array(mirror, "guards");
        for (int i = 0; i < guardNames.size() && hasGuardHost; i++) {
            if (!seenGuards.add(guardNames.get(i))) {
                diagnostics.error("SG105", "the guard " + guardNames.get(i) + " is declared twice", element, mirror, guardValues.get(i));
                continue;
            }
            bindings.resolve(guardsBy, guardNames.get(i), Bindings.Role.GUARD, types, Optional.empty(), element, mirror, guardValues.get(i))
                    .ifPresent(guards::add);
        }
        Optional<Binding> hook = Optional.empty();
        if (!afterCommit.isEmpty() && hasBy) {
            hook = bindings.resolve(by, afterCommit, Bindings.Role.HOOK, types, Optional.empty(), element, mirror, annotations.value(mirror, "afterCommit"));
        }
        if (timeout.isPresent() && actionBinding.isPresent() && !actionBinding.get().async()) {
            diagnostics.error("SG507", "timeout is only allowed on an asynchronous action", element, mirror, annotations.value(mirror, "timeout"));
        }
        if (diagnostics.errors() > before) {
            return Optional.empty();
        }
        EdgeKind kind = action ? EdgeKind.ACTION : EdgeKind.MOVE;
        return Optional.of(new CellModel(from, to, kind, action ? actionName : "", actionBinding, guards, onFailure, failsOn, timeout, hook));
    }

    private Optional<Duration> parseTimeout(String text, Element element, AnnotationMirror mirror, AnnotationValue anchor) {
        try {
            Duration duration = Duration.parse(text);
            if (duration.isZero() || duration.isNegative()) {
                diagnostics.error("SG511", "timeout must be positive: " + text, element, mirror, anchor);
                return Optional.empty();
            }
            return Optional.of(duration);
        } catch (DateTimeParseException e) {
            diagnostics.error("SG511", "timeout must be an ISO-8601 duration such as PT5S, not \"" + text + "\"", element, mirror, anchor);
            return Optional.empty();
        }
    }

    private boolean isForbiddenFailsOn(TypeMirror type) {
        return signatures.erasureIs(type, "java.lang.Throwable") || signatures.erasureIs(type, "java.lang.Exception")
                || signatures.erasureIs(type, "java.lang.RuntimeException") || signatures.subtype(type, "java.lang.Error")
                || signatures.subtype(type, "io.stategraph.error.StateMachineException");
    }

    private void checkDuplicateCells(TypeElement type, List<ForbidModel> forbids, List<CellModel> cells, List<OperationModel> operations) {
        Set<String> seen = new LinkedHashSet<>();
        for (ForbidModel forbid : forbids) {
            if (!seen.add(forbid.from() + " → " + forbid.to())) {
                diagnostics.error("SG102", "the cell " + forbid.from() + " → " + forbid.to() + " is declared twice", type);
            }
        }
        List<CellModel> all = new ArrayList<>(cells);
        for (OperationModel operation : operations) {
            all.add(operation.cell());
        }
        for (CellModel cell : all) {
            for (String from : cell.from()) {
                if (!seen.add(from + " → " + cell.to())) {
                    diagnostics.error("SG102", "the cell " + from + " → " + cell.to() + " is declared twice", type);
                }
            }
        }
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
