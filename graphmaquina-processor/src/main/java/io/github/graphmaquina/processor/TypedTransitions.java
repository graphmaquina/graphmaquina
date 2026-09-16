package io.github.graphmaquina.processor;

import io.github.graphmaquina.api.TransitionKind;
import io.github.graphmaquina.processor.model.Binding;
import io.github.graphmaquina.processor.model.MachineTypes;
import io.github.graphmaquina.processor.model.TransitionModel;
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
 * Normalizes one {@code Transition} of the generated vocabulary into the {@link TransitionModel} the generators
 * read.
 *
 * <p>An edge is keyed by its identifier and its origin, so {@code id} is read first and never inferred: neither
 * the action's name nor the operation's Java name has any part in it. An absent action makes the edge a move
 * and a single one makes it an action; everything else is shared between the two.</p>
 *
 * <p>Every state and every identifier is read as the constant it is and checked against the machine's own enum
 * before its name is used, and every symbol is resolved to the method the generated code calls. No name reaches
 * the model without one of those two checks having accepted it first.</p>
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

    /** The identifier {@code mirror} declares, or empty when javac could not read it as a constant of {@code machine}. */
    Optional<String> identifier(AnnotationMirror mirror, MachineTypes machine) {
        return annotations.find(mirror, "id")
                .flatMap((AnnotationValue value) -> TypedValues.constantName(value, machine.transitionsElement()));
    }

    /**
     * The edge {@code mirror} declares on {@code element}, or empty when it has a problem, which was reported.
     * {@code operation} is the name of the method an operation declares it on, and is empty for a transition of
     * the interface itself, whose label is the action it selected. {@code defaultAfterCommit} is the hook the
     * enclosing machine declares as its default, used when this declaration names none of its own.
     */
    Optional<TransitionModel> read(Element element, AnnotationMirror mirror, MachineTypes machine,
            String operation, Optional<Binding> defaultAfterCommit) {
        int before = diagnostics.errors();
        Optional<AnnotationValue> declaredId = annotations.find(mirror, "id");
        Optional<AnnotationValue> declaredFrom = annotations.find(mirror, "from");
        Optional<AnnotationValue> declaredTo = annotations.find(mirror, "to");
        if (declaredId.isEmpty() || declaredFrom.isEmpty() || declaredTo.isEmpty()) {
            // javac refuses an annotation that omits an element it requires, and leaves that element out.
            return Optional.empty();
        }
        Optional<String> id = TypedValues.constantName(declaredId.get(), machine.transitionsElement());
        List<AnnotationValue> fromValues = annotations.array(mirror, "from");
        structure.checkFrom(!fromValues.isEmpty(), DiagnosticAnchor.of(element, mirror, declaredFrom.get()));
        Optional<List<String>> from = TypedValues.constantNames(fromValues, machine.statesElement());
        Optional<String> to = TypedValues.constantName(declaredTo.get(), machine.statesElement());
        if (id.isEmpty() || from.isEmpty() || to.isEmpty()) {
            return Optional.empty();
        }

        Optional<AnnotationValue> declaredAction = single(element, mirror, "action");
        Optional<Binding> action = declaredAction.flatMap((AnnotationValue value) ->
                resolve(value, Bindings.Role.ACTION, element, mirror, machine));
        List<Binding> guards = guards(element, mirror, machine);
        Optional<AnnotationValue> declaredTimeout = single(element, mirror, "timeout");
        Optional<Duration> timeout = declaredTimeout.flatMap((AnnotationValue value) -> duration(value, element, mirror));
        Optional<String> onFailure = single(element, mirror, "onFailure")
                .flatMap((AnnotationValue value) -> TypedValues.constantName(value, machine.transitionsElement()));
        List<AnnotationValue> failsOnValues = annotations.find(mirror, "failsOn").isEmpty()
                ? List.of()
                : annotations.array(mirror, "failsOn");
        List<TypeMirror> failsOn = classes(failsOnValues);
        structure.checkFailsOn(failsOn, failsOnValues, element, mirror);
        Optional<Binding> afterCommit = afterCommit(element, mirror, machine)
                .or(() -> defaultAfterCommit);

        structure.checkActionRequired(declaredAction.isPresent(), onFailure, failsOn, timeout, "",
                DiagnosticAnchor.of(element, mirror, declaredTo.get()));
        structure.checkAsyncTimeout(timeout, action,
                DiagnosticAnchor.of(element, mirror, declaredTimeout.orElse(declaredTo.get())));
        if (diagnostics.errors() > before) {
            return Optional.empty();
        }
        return Optional.of(new TransitionModel(id.get(), from.get(), to.get(),
                action.isPresent() ? TransitionKind.ACTION : TransitionKind.MOVE, label(action, operation),
                action, guards, onFailure, failsOn, timeout, afterCommit));
    }

    /** Resolves the optional hook on a definition or transition at its own declaration. */
    Optional<Binding> afterCommit(Element element, AnnotationMirror mirror, MachineTypes machine) {
        return single(element, mirror, "afterCommit")
                .flatMap((AnnotationValue value) -> resolve(value, Bindings.Role.HOOK, element, mirror, machine));
    }

    /**
     * The one value of the element {@code name}, and empty when this annotation does not declare that element
     * at all, which is how an older vocabulary without a default hook resolves here instead of crashing.
     */
    private Optional<AnnotationValue> single(Element element, AnnotationMirror mirror, String name) {
        Optional<AnnotationValue> declared = annotations.find(mirror, name);
        if (declared.isEmpty()) {
            // annotations.array(...) throws IllegalArgumentException for a member the annotation type does not declare.
            return Optional.empty();
        }
        return TypedValues.optional(mirror, name, annotations,
                DiagnosticAnchor.of(element, mirror, declared.get()), diagnostics);
    }

    /** The guards the edge asks, in declaration order; {@code SG105} for one the declaration names twice. */
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

    /** The label of the edge: the operation's own name, the selected action's for a type-level edge, empty for a move. */
    private static String label(Optional<Binding> action, String operation) {
        if (action.isEmpty()) {
            return "";
        }
        return operation.isEmpty() ? action.get().methodName() : operation;
    }
}
