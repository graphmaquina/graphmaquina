package io.github.graphmaquina.processor;

import io.github.graphmaquina.dsl.GraphOutlineValidator;
import io.github.graphmaquina.error.InvalidGraphException;
import io.github.graphmaquina.processor.model.Binding;
import io.github.graphmaquina.processor.model.CellModel;
import io.github.graphmaquina.processor.model.ForbidModel;
import io.github.graphmaquina.processor.model.MachineModel;
import io.github.graphmaquina.processor.model.MachineTypes;
import io.github.graphmaquina.processor.model.OperationModel;
import java.time.Duration;
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
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import lombok.RequiredArgsConstructor;

/**
 * What every graph must hold whatever the declaration that spells it: the shape of the facade, of one
 * operation and of one cell, and the structure of the whole matrix once it is assembled.
 *
 * <p>None of these rules reads an annotation. They are asked by the readers of the typed vocabulary, each with
 * the values it has already resolved and the anchor it wants the answer reported at, so a rule is stated once
 * and a diagnostic still points at the declaration the author wrote.</p>
 */
@RequiredArgsConstructor
final class StructuralChecks {

    static final String MACHINE = "io.github.graphmaquina.api.Machine";

    private final Signatures signatures;
    private final Diagnostics diagnostics;

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

    /** SG102 for every pair of states two declarations of the same machine both claim. */
    void checkDuplicateCells(TypeElement type, List<ForbidModel> forbids, List<CellModel> cells, List<OperationModel> operations) {
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

    /** SG502 unless {@code type} is top-level: the generated companion lives next to it in its package. */
    boolean checkTopLevel(TypeElement type, DiagnosticAnchor anchor) {
        if (type.getNestingKind() == NestingKind.TOP_LEVEL) {
            return true;
        }
        anchor.report(diagnostics, "SG502", type.getSimpleName() + " must be a top-level interface: the generated companion"
                + " lives next to it in the package");
        return false;
    }

    /** Whether {@code method} must declare a cell: a default, static or private declaration is not an operation. */
    static boolean isOperation(ExecutableElement method) {
        return !method.isDefault() && !method.getModifiers().contains(Modifier.STATIC)
                && !method.getModifiers().contains(Modifier.PRIVATE);
    }

    /** SG504 unless the operation carries exactly one of the two annotations that declare its cell. */
    boolean checkOperationKinds(ExecutableElement method, boolean action, boolean move) {
        if (action == move) {
            diagnostics.error("SG504", method.getSimpleName() + " must carry exactly one Action or Move of its own graph vocabulary", method);
            return false;
        }
        return true;
    }

    /**
     * SG504 unless the operation is declared as {@code Outcome<E, S> operation(E)} or as
     * {@code Outcome<E, S> operation(E, I)}, the form that takes the input of one call. The payload type is
     * whatever javac resolved, so an inaccessible or unknown one is its own diagnostic; an operation that takes
     * one may not be varargs nor declare type parameters of its own, because neither names a payload type the
     * facade could declare.
     */
    boolean checkOperationSignature(ExecutableElement method, MachineTypes types) {
        DeclaredType outcome = signatures.declared("io.github.graphmaquina.api.Outcome", types.entity(), types.states());
        int arity = method.getParameters().size();
        boolean supported = arity == 1 || (arity == 2 && !method.isVarArgs()
                && method.getTypeParameters().isEmpty());
        if (supported && signatures.same(method.getParameters().get(0).asType(), types.entity())
                && signatures.same(method.getReturnType(), outcome)) {
            return true;
        }
        diagnostics.error("SG504", "an operation is declared as Outcome<" + types.entityName() + ", " + types.statesName() + "> "
                + method.getSimpleName() + "(" + types.entityName() + ") or " + method.getSimpleName() + "("
                + types.entityName() + ", I), without varargs or method type parameters on input operations", method);
        return false;
    }

    /** SG507 unless the cell names at least one state to leave. */
    void checkFrom(boolean declared, DiagnosticAnchor anchor) {
        if (!declared) {
            anchor.report(diagnostics, "SG507", "from must name at least one state");
        }
    }

    /** SG104 when a cell leaves a state to reach that same state: the diagonal is always STAY. */
    void checkDiagonal(List<String> from, String to, DiagnosticAnchor anchor) {
        if (from.contains(to)) {
            anchor.report(diagnostics, "SG104", to + " is both in from and to: a cell cannot move to itself;"
                    + " the diagonal is always STAY");
        }
    }

    /** SG302 for every exception failsOn reserves, reported at the value that names it. */
    void checkFailsOn(List<TypeMirror> failsOn, List<AnnotationValue> values, Element element, AnnotationMirror mirror) {
        for (int i = 0; i < failsOn.size(); i++) {
            if (isForbiddenFailsOn(failsOn.get(i))) {
                diagnostics.error("SG302", "failsOn does not allow " + failsOn.get(i) + "; only exceptions foreign to the library, and never"
                        + " Throwable, Exception, RuntimeException or an Error", element, mirror, values.get(i));
            }
        }
    }

    /** SG507 when a cell that runs no action still declares what only an action can use. {@code remedy} ends the message. */
    void checkActionRequired(boolean action, Optional<String> onFailure, List<TypeMirror> failsOn, Optional<Duration> timeout,
            String remedy, DiagnosticAnchor anchor) {
        if (!action && (onFailure.isPresent() || !failsOn.isEmpty() || timeout.isPresent())) {
            anchor.report(diagnostics, "SG507", "onFailure, failsOn and timeout need an action" + remedy);
        }
    }

    /** SG507 when a timeout is declared on an action that does not run asynchronously. */
    void checkAsyncTimeout(Optional<Duration> timeout, Optional<Binding> action, DiagnosticAnchor anchor) {
        if (timeout.isPresent() && action.isPresent() && !action.get().async()) {
            anchor.report(diagnostics, "SG507", "timeout is only allowed on an asynchronous action");
        }
    }

    /** SG506 for every state that is neither terminal nor closed by a row of its own. */
    void checkRowsClosed(MachineTypes types, Set<String> closed, List<String> terminals, DiagnosticAnchor anchor) {
        for (String state : types.stateNames()) {
            if (!closed.contains(state) && !terminals.contains(state)) {
                anchor.report(diagnostics, "SG506", state + " is not terminal and closes no Row: close its row with the reason"
                        + " every transition it does not declare is refused for");
            }
        }
    }

    /** Every problem the full outline of {@code model} has, reported where its graph is declared. */
    void checkOutline(MachineModel model, DiagnosticAnchor anchor) {
        for (InvalidGraphException.Problem problem : GraphOutlineValidator.validate(Outlines.of(model))) {
            anchor.report(diagnostics, problem.code(), problem.message());
        }
    }

    private boolean isForbiddenFailsOn(TypeMirror type) {
        return signatures.erasureIs(type, "java.lang.Throwable") || signatures.erasureIs(type, "java.lang.Exception")
                || signatures.erasureIs(type, "java.lang.RuntimeException") || signatures.subtype(type, "java.lang.Error")
                || signatures.subtype(type, "io.github.graphmaquina.error.StateMachineException");
    }
}
