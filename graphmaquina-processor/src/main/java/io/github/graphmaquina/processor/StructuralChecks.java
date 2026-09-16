package io.github.graphmaquina.processor;

import io.github.graphmaquina.dsl.MachineOutlineValidator;
import io.github.graphmaquina.error.InvalidGraphException;
import io.github.graphmaquina.processor.model.Binding;
import io.github.graphmaquina.processor.model.MachineModel;
import io.github.graphmaquina.processor.model.MachineTypes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
 * operation and of one edge, and the structure of the whole graph once it is assembled.
 *
 * <p>None of these rules reads an annotation. They are asked by the readers of the typed vocabulary, each with
 * the values it has already resolved and the anchor it wants the answer reported at, so a rule is stated once
 * and a diagnostic still points at the declaration the author wrote.</p>
 *
 * <p>Everything about the assembled graph — unique keys, policies, reachability, terminals, cycles, failure
 * routes and watches — is asked of {@link MachineOutlineValidator}, the very validator the DSL runs, so a
 * machine declared in either language is refused for the same reason under the same code.</p>
 */
@RequiredArgsConstructor
final class StructuralChecks {

    static final String MACHINE = "io.github.graphmaquina.api.Machine";

    private final Signatures signatures;
    private final Diagnostics diagnostics;

    /** E, S and T from {@code extends Machine<E, S, T>}, searched through the supertypes; empty when not resolvable. */
    Optional<MachineTypes> machineTypes(TypeElement type) {
        if (type.getKind() != ElementKind.INTERFACE) {
            return Optional.empty();
        }
        Optional<DeclaredType> machine = findMachine(type.asType());
        if (machine.isEmpty() || machine.get().getTypeArguments().size() != 3) {
            return Optional.empty();
        }
        TypeMirror entity = machine.get().getTypeArguments().get(0);
        TypeMirror states = machine.get().getTypeArguments().get(1);
        TypeMirror transitions = machine.get().getTypeArguments().get(2);
        if (!(entity instanceof DeclaredType entityType) || !(states instanceof DeclaredType statesType)
                || !(transitions instanceof DeclaredType transitionsType)
                || !signatures.isEnum(states) || !signatures.isEnum(transitions)
                || signatures.same(states, transitions)) {
            return Optional.empty();
        }
        TypeElement statesElement = (TypeElement) statesType.asElement();
        TypeElement transitionsElement = (TypeElement) transitionsType.asElement();
        return Optional.of(new MachineTypes(type, entityType, (TypeElement) entityType.asElement(), statesType,
                statesElement, constants(statesElement), transitionsType, transitionsElement,
                constants(transitionsElement)));
    }

    /** The constants of an enum, in the order it declares them. */
    private static List<String> constants(TypeElement type) {
        List<String> names = new ArrayList<>();
        for (Element member : type.getEnclosedElements()) {
            if (member.getKind() == ElementKind.ENUM_CONSTANT) {
                names.add(member.getSimpleName().toString());
            }
        }
        return names;
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

    /** SG502 unless {@code type} is top-level: the generated companion lives next to it in its package. */
    boolean checkTopLevel(TypeElement type, DiagnosticAnchor anchor) {
        if (type.getNestingKind() == NestingKind.TOP_LEVEL) {
            return true;
        }
        anchor.report(diagnostics, "SG502", type.getSimpleName() + " must be a top-level interface: the generated companion"
                + " lives next to it in the package");
        return false;
    }

    /** Whether {@code method} must declare an edge: a default, static or private declaration is not an operation. */
    static boolean isOperation(ExecutableElement method) {
        return !method.isDefault() && !method.getModifiers().contains(Modifier.STATIC)
                && !method.getModifiers().contains(Modifier.PRIVATE);
    }

    /** SG504 unless the operation carries at least one Transition of its own graph vocabulary. */
    boolean checkOperationDeclared(ExecutableElement method, boolean declared) {
        if (!declared) {
            diagnostics.error("SG504", method.getSimpleName() + " must carry at least one Transition of its own"
                    + " graph vocabulary", method);
            return false;
        }
        return true;
    }

    /**
     * SG504 unless the operation is declared as {@code Outcome<E, S, T> operation(E)} or as
     * {@code Outcome<E, S, T> operation(E, I)}, the form that takes the input of one call. The payload type is
     * whatever javac resolved, so an inaccessible or unknown one is its own diagnostic; an operation that takes
     * one may not be varargs nor declare type parameters of its own, because neither names a payload type the
     * facade could declare.
     */
    boolean checkOperationSignature(ExecutableElement method, MachineTypes types) {
        DeclaredType outcome = signatures.declared("io.github.graphmaquina.api.Outcome", types.entity(),
                types.states(), types.transitions());
        int arity = method.getParameters().size();
        boolean supported = arity == 1 || (arity == 2 && !method.isVarArgs()
                && method.getTypeParameters().isEmpty());
        if (supported && signatures.same(method.getParameters().get(0).asType(), types.entity())
                && signatures.same(method.getReturnType(), outcome)) {
            return true;
        }
        diagnostics.error("SG504", "an operation is declared as Outcome<" + types.entityName() + ", " + types.statesName()
                + ", " + types.transitionsName() + "> " + method.getSimpleName() + "(" + types.entityName() + ") or "
                + method.getSimpleName() + "(" + types.entityName() + ", I), without varargs or method type"
                + " parameters on input operations", method);
        return false;
    }

    /** SG507 unless the edge names at least one state to leave. */
    void checkFrom(boolean declared, DiagnosticAnchor anchor) {
        if (!declared) {
            anchor.report(diagnostics, "SG507", "from must name at least one state");
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

    /** SG507 when an edge that runs no action still declares what only an action can use. {@code remedy} ends the message. */
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

    /** Every problem the full outline of {@code model} has, reported where its graph is declared. */
    void checkOutline(MachineModel model, DiagnosticAnchor anchor) {
        for (InvalidGraphException.Problem problem : MachineOutlineValidator.validate(Outlines.of(model))) {
            anchor.report(diagnostics, problem.code(), problem.message());
        }
    }

    private boolean isForbiddenFailsOn(TypeMirror type) {
        return signatures.erasureIs(type, "java.lang.Throwable") || signatures.erasureIs(type, "java.lang.Exception")
                || signatures.erasureIs(type, "java.lang.RuntimeException") || signatures.subtype(type, "java.lang.Error")
                || signatures.subtype(type, "io.github.graphmaquina.error.StateMachineException");
    }
}
