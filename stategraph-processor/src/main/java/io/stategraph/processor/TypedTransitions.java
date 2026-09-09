package io.stategraph.processor;

import io.stategraph.api.EdgeKind;
import io.stategraph.processor.model.Binding;
import io.stategraph.processor.model.CellModel;
import io.stategraph.processor.model.MachineTypes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.type.TypeMirror;

/**
 * Normalizes the three vocabulary annotations that declare a transition into the one {@link CellModel} the
 * generators already read.
 *
 * <p>An {@code Action} names the action it runs, a {@code Move} declares no action at all, and a {@code Cell}
 * spans both: an absent action makes it a move and a single one makes it an action. Everything else is shared,
 * so each element is read from the mirror that declares it and is simply absent from the kinds that do not,
 * which is what tells a move from an action without asking which annotation was written.</p>
 *
 * <p>Every state is read as the constant it is and checked against the machine's own enum before its name is
 * used, and every symbol is resolved to the method the generated code calls. No name reaches the model without
 * one of those two checks having accepted it first.</p>
 */
final class TypedTransitions {

    private static final String DUPLICATE_GUARD = "SG105";

    private final StructuralChecks structure;
    private final SymbolResolver symbols;
    private final TypedTimeouts timeouts;
    private final Annotations annotations;
    private final Diagnostics diagnostics;

    TypedTransitions(StructuralChecks structure, SymbolResolver symbols, TypedTimeouts timeouts,
            Annotations annotations, Diagnostics diagnostics) {
        this.structure = structure;
        this.symbols = symbols;
        this.timeouts = timeouts;
        this.annotations = annotations;
        this.diagnostics = diagnostics;
    }

    /**
     * The cell {@code mirror} declares on {@code element}, or empty when it has a problem, which was reported.
     * {@code operation} is the name of the method an operation declares it on, and is empty for a {@code Cell}
     * of the interface itself, whose label is the action it selected.
     */
    Optional<CellModel> read(Element element, AnnotationMirror mirror, MachineTypes machine, String operation) {
        int before = diagnostics.errors();
        Optional<AnnotationValue> declaredFrom = annotations.find(mirror, "from");
        Optional<AnnotationValue> declaredTo = annotations.find(mirror, "to");
        if (declaredFrom.isEmpty() || declaredTo.isEmpty()) {
            // javac refuses an annotation that omits an element it requires, and leaves that element out.
            return Optional.empty();
        }
        List<AnnotationValue> fromValues = annotations.array(mirror, "from");
        structure.checkFrom(!fromValues.isEmpty(), DiagnosticAnchor.of(element, mirror, declaredFrom.get()));
        Optional<List<String>> from = TypedValues.stateNames(fromValues, machine.statesElement());
        Optional<String> to = TypedValues.stateName(declaredTo.get(), machine.statesElement());
        if (from.isEmpty() || to.isEmpty()) {
            return Optional.empty();
        }
        structure.checkDiagonal(from.get(), to.get(), DiagnosticAnchor.of(element, mirror, declaredTo.get()));

        Optional<AnnotationValue> declaredAction = single(element, mirror, "action");
        Optional<Binding> action = declaredAction.flatMap((AnnotationValue value) ->
                resolve(value, Bindings.Role.ACTION, element, mirror, machine));
        List<Binding> guards = guards(element, mirror, machine);
        Optional<AnnotationValue> declaredTimeout = single(element, mirror, "timeout");
        Optional<Duration> timeout = declaredTimeout.flatMap((AnnotationValue value) -> duration(value, element, mirror));
        Optional<String> onFailure = single(element, mirror, "onFailure")
                .flatMap((AnnotationValue value) -> TypedValues.stateName(value, machine.statesElement()));
        List<AnnotationValue> failsOnValues = annotations.find(mirror, "failsOn").isEmpty()
                ? List.of()
                : annotations.array(mirror, "failsOn");
        List<TypeMirror> failsOn = classes(failsOnValues);
        structure.checkFailsOn(failsOn, failsOnValues, element, mirror);
        Optional<Binding> afterCommit = single(element, mirror, "afterCommit")
                .flatMap((AnnotationValue value) -> resolve(value, Bindings.Role.HOOK, element, mirror, machine));

        structure.checkActionRequired(declaredAction.isPresent(), onFailure, failsOn, timeout, "",
                DiagnosticAnchor.of(element, mirror, declaredTo.get()));
        structure.checkAsyncTimeout(timeout, action,
                DiagnosticAnchor.of(element, mirror, declaredTimeout.orElse(declaredTo.get())));
        if (diagnostics.errors() > before) {
            return Optional.empty();
        }
        return Optional.of(new CellModel(from.get(), to.get(), action.isPresent() ? EdgeKind.ACTION : EdgeKind.MOVE,
                label(action, operation), action, guards, onFailure, failsOn, timeout, afterCommit));
    }

    /**
     * The one value of the element {@code name}, and empty when this annotation does not declare that element
     * at all, which is how a {@code Move} says it runs no action and a {@code Cell} that it may run one.
     */
    private Optional<AnnotationValue> single(Element element, AnnotationMirror mirror, String name) {
        Optional<AnnotationValue> declared = annotations.find(mirror, name);
        if (declared.isEmpty()) {
            return Optional.empty();
        }
        return TypedValues.optional(mirror, name, annotations,
                DiagnosticAnchor.of(element, mirror, declared.get()), diagnostics);
    }

    /** The guards the cell asks, in declaration order; {@code SG105} for one the declaration names twice. */
    private List<Binding> guards(Element element, AnnotationMirror mirror, MachineTypes machine) {
        List<Binding> guards = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (AnnotationValue value : annotations.array(mirror, "guards")) {
            if (!(value.getValue() instanceof TypeMirror symbol)) {
                continue;
            }
            if (!seen.add(symbol.toString())) {
                DiagnosticAnchor.of(element, mirror, value).report(diagnostics, DUPLICATE_GUARD,
                        "the guard " + symbol + " is declared twice");
                continue;
            }
            resolve(value, Bindings.Role.GUARD, element, mirror, machine).ifPresent(guards::add);
        }
        return guards;
    }

    /** The method {@code value} names when it is used as {@code role}, or empty after the resolver reported why. */
    private Optional<Binding> resolve(AnnotationValue value, Bindings.Role role, Element element,
            AnnotationMirror mirror, MachineTypes machine) {
        if (!(value.getValue() instanceof TypeMirror symbol)) {
            // javac has already refused a value it could not read as the symbol the element declares.
            return Optional.empty();
        }
        return symbols.resolve(symbol, role, machine, Optional.empty(), DiagnosticAnchor.of(element, mirror, value));
    }

    /** The timeout {@code value} holds, read from the nested declaration it is written as. */
    private Optional<Duration> duration(AnnotationValue value, Element element, AnnotationMirror mirror) {
        if (!(value.getValue() instanceof AnnotationMirror timeout)) {
            return Optional.empty();
        }
        return timeouts.read(timeout, DiagnosticAnchor.of(element, mirror, value));
    }

    /** The exception types an array element names, skipping any value javac could not read as a class. */
    private static List<TypeMirror> classes(List<AnnotationValue> values) {
        List<TypeMirror> types = new ArrayList<>();
        for (AnnotationValue value : values) {
            if (value.getValue() instanceof TypeMirror type) {
                types.add(type);
            }
        }
        return types;
    }

    /** The label of the cell: the operation's own name, the selected action's for a cell, empty for a move. */
    private static String label(Optional<Binding> action, String operation) {
        if (action.isEmpty()) {
            return "";
        }
        return operation.isEmpty() ? action.get().methodName() : operation;
    }
}
