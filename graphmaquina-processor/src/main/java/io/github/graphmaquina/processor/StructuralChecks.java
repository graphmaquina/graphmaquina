package io.github.graphmaquina.processor;

import io.github.graphmaquina.codegen.emit.SymbolNames;
import io.github.graphmaquina.dsl.MachineOutlineValidator;
import io.github.graphmaquina.error.InvalidGraphException;
import io.github.graphmaquina.internal.FailureTypes;
import io.github.graphmaquina.processor.model.Binding;
import io.github.graphmaquina.processor.model.MachineModel;
import io.github.graphmaquina.processor.model.MachineTypes;
import io.github.graphmaquina.processor.model.RecoveryModel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
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
 * <p>Everything about the assembled graph — policies, reachability, terminals, cycles, failure routes, watches and
 * the exits of sub-machines — is asked of {@link MachineOutlineValidator}, the very validator the DSL runs, so a
 * machine declared in either language is refused for the same reason under the same code. A key two edges claim,
 * a state given two policies, a rejection declared twice and a blank reason never reach it:
 * {@link TypedMachineModelBuilder} reports them first, as that validator's {@code SG102} and {@code SG103}, and
 * builds no model.</p>
 */
@RequiredArgsConstructor
final class StructuralChecks {

    static final String MACHINE = "io.github.graphmaquina.api.Machine";

    /** The types whose methods a machine's companion implements whatever the facade declares. */
    private static final List<String> BUILT_IN = List.of(MACHINE, "java.lang.Object");

    private static final String FAILURE_KIND = "io.github.graphmaquina.api.FailureKind";

    private static final String GRAPH_DEFINITION = "io.github.graphmaquina.api.GraphDefinition";

    /** What SG304 says after the binary name of a throwable type one edge declares twice. */
    private static final String REPEATED_TYPE = " is declared more than once across recover and failsOn; a throwable"
            + " type selects a single route";

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

    /**
     * Whether the entity of {@code types} is a parameterized type, through its own type arguments or through those of
     * a class it is an inner class of, as {@code Store<String>.Doc} is. Generated code names the entity by its class,
     * as a class literal and as the host of its property symbols, whose class literals are raw, so it could only
     * ever compare and name it raw; such a machine is refused where it is declared.
     */
    static boolean hasParameterizedEntity(MachineTypes types) {
        return hasTypeArguments(types.entity());
    }

    /**
     * Whether {@code type} has type arguments, its own or those of a class it is an inner class of: a static nested
     * class has no enclosing type, so the type arguments of the class it is nested in never count.
     */
    static boolean hasTypeArguments(TypeMirror type) {
        TypeMirror current = type;
        while (current instanceof DeclaredType declared) {
            if (!declared.getTypeArguments().isEmpty()) {
                return true;
            }
            current = declared.getEnclosingType();
        }
        return false;
    }

    /** SG502 unless {@code type} is top-level: the generated companion lives next to it in its package. */
    boolean checkTopLevel(TypeElement type, DiagnosticAnchor anchor) {
        if (type.getNestingKind() == NestingKind.TOP_LEVEL) {
            return true;
        }
        anchor.report(diagnostics, "SG502", type.getSimpleName()
                + " must be a top-level interface: the generated companion lives next to it in the package");
        return false;
    }

    /**
     * The method of {@code Machine}, or the public method of {@code Object}, that {@code method}, which
     * {@code facade} declares itself, overrides as a member of {@code facade}, if there is one. The companion writes
     * or inherits every one of them, so a redeclaration of one is no operation, and an operation cannot be one.
     */
    Optional<ExecutableElement> overriddenBuiltIn(ExecutableElement method, TypeElement facade) {
        return signatures.overridden(method, facade, BUILT_IN);
    }

    /**
     * SG504 for every abstract method {@code facade} inherits from a super-interface other than {@code Machine},
     * except one that the method the companion writes or inherits for a method of {@code Machine} or a public
     * method of {@code Object} already implements, as {@link Signatures#implemented} decides: operations are read
     * from the facade's own declarations, so the companion would not implement any other inherited method. A
     * redeclaration of one of those methods whose result the companion's method cannot return is told apart, since
     * declaring it on the facade would be refused as well.
     */
    void checkInheritedOperations(TypeElement facade) {
        for (ExecutableElement method : signatures.inheritedAbstract(facade, BUILT_IN)) {
            String from = ((TypeElement) method.getEnclosingElement()).getQualifiedName().toString();
            Optional<ExecutableElement> builtIn = signatures.redeclared(method, facade, BUILT_IN);
            if (builtIn.isPresent()) {
                String owner = ((TypeElement) builtIn.get().getEnclosingElement()).getSimpleName() + "."
                        + builtIn.get().getSimpleName();
                diagnostics.error("SG504", facade.getSimpleName() + " inherits " + method.getSimpleName() + " from "
                        + from + ", which redeclares " + owner + " with a result the generated companion's "
                        + method.getSimpleName() + " cannot return: declare it in " + from + " with the result " + owner
                        + " has, or leave the redeclaration out", facade);
                continue;
            }
            diagnostics.error("SG504", facade.getSimpleName() + " inherits the abstract method "
                    + method.getSimpleName() + " from " + from
                    + ", which the generated companion would not implement: declare every operation on "
                    + facade.getSimpleName() + " itself", facade);
        }
    }

    /**
     * SG504 when {@code method}, which {@code facade} declares and which overrides {@code builtIn}, carries
     * vocabulary, since it would be an operation, or a check, the companion cannot declare beside the implementation
     * of {@code builtIn} it already has; otherwise SG504 when it declares a result that the companion's {@code builtIn}
     * cannot stand for, such as a subtype of the result {@code Machine} declares, since the companion would then
     * not implement it at all.
     */
    void checkNotAnOperation(ExecutableElement method, ExecutableElement builtIn, TypeElement facade,
                             boolean declared) {
        String owner = ((TypeElement) builtIn.getEnclosingElement()).getSimpleName() + "." + builtIn.getSimpleName();
        if (declared) {
            diagnostics.error("SG504", method.getSimpleName() + " overrides " + owner
                    + ", which the generated companion implements already: give the method another name", method);
        } else if (!signatures.implementedBy(method, builtIn, facade)) {
            diagnostics.error("SG504", method.getSimpleName() + " redeclares " + owner + " with a result the generated"
                    + " companion's " + builtIn.getSimpleName() + " cannot return: declare it with the result "
                    + owner + " has, or leave the redeclaration out", method);
        }
    }

    /**
     * Whether {@code method} is read for the vocabulary it carries, as an operation or as a check: a default, static
     * or private declaration is neither.
     */
    static boolean isOperation(ExecutableElement method) {
        return !method.isDefault() && !method.getModifiers().contains(Modifier.STATIC)
                && !method.getModifiers().contains(Modifier.PRIVATE);
    }

    /**
     * SG504 unless the operation carries at least one Transition of its own graph vocabulary. An abstract method of
     * the facade that carries a Check of that vocabulary instead is a check, and never asked.
     */
    boolean checkOperationDeclared(ExecutableElement method, boolean declared) {
        if (!declared) {
            diagnostics.error("SG504", method.getSimpleName() + " must carry at least one Transition, or one Check,"
                    + " of its own graph vocabulary", method);
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
        diagnostics.error("SG504", "an operation is declared as Outcome<" + types.entityName() + ", "
                + types.statesName() + ", " + types.transitionsName() + "> " + method.getSimpleName() + "("
                + types.entityName() + ") or " + method.getSimpleName() + "(" + types.entityName()
                + ", I), without varargs or method type parameters on input operations", method);
        return false;
    }

    /** SG507 unless the edge names at least one state to leave. */
    void checkFrom(boolean declared, DiagnosticAnchor anchor) {
        if (!declared) {
            anchor.report(diagnostics, "SG507", "from must name at least one state");
        }
    }

    /**
     * SG302 for every throwable type failsOn reserves, reported at the value that names it: the catch-alls, an
     * {@code Error} and a {@code CancellationException}, which the runtime never treats as a domain failure, the
     * library's own exceptions, and exactly {@code CompletionException} and {@code ExecutionException}, which the
     * runtime unwraps before it matches, so only the type of their cause can ever match.
     */
    void checkFailsOn(List<TypeMirror> failsOn, List<AnnotationValue> values, Element element,
                      AnnotationMirror mirror) {
        for (int i = 0; i < failsOn.size(); i++) {
            if (isForbiddenFailsOn(failsOn.get(i))) {
                diagnostics.error("SG302", "failsOn does not allow " + failsOn.get(i)
                        + "; only throwable types foreign to the library, and never Throwable, Exception,"
                        + " RuntimeException, an Error or a CancellationException, which are never domain failures,"
                        + " nor CompletionException or ExecutionException, which are unwrapped before matching:"
                        + " declare the type of their cause",
                        element, mirror, values.get(i));
            }
        }
    }

    /**
     * SG507 when an edge that runs no action still declares what only an action can use. {@code remedy} ends the
     * message.
     */
    void checkActionRequired(boolean action, Optional<String> onFailure, List<TypeMirror> failsOn,
            Optional<Duration> timeout, String remedy, DiagnosticAnchor anchor) {
        if (!action && (onFailure.isPresent() || !failsOn.isEmpty() || timeout.isPresent())) {
            anchor.report(diagnostics, "SG507", "onFailure, failsOn and timeout need an action" + remedy);
        }
    }

    /**
     * SG507 when an edge that runs no action still declares recovery rules, which only a failure of an action
     * selects; reported apart from what {@link #checkActionRequired} reports. {@code remedy} ends the message.
     */
    void checkRecoveriesNeedAction(boolean action, List<RecoveryModel> recoveries, String remedy,
            DiagnosticAnchor anchor) {
        if (!action && !recoveries.isEmpty()) {
            anchor.report(diagnostics, "SG507", "recoveries need an action" + remedy);
        }
    }

    /**
     * What the recovery rules of one edge must hold, each problem reported at the value it is about and naming each
     * kind and type by its binary name.
     *
     * <ul>
     *   <li>{@code SG302} for every throwable type a rule lists that the edge's own failsOn may not list either.</li>
     *   <li>{@code SG303} at the first occurrence of a kind that is not a failure kind, as {@link #isFailureKind}
     *       decides. Whether the facade's package can access the kind is javac's to say: the facade writes its class
     *       literal itself.</li>
     *   <li>{@code SG304} at the second occurrence of a kind two rules name, and of a throwable type listed twice
     *       within one rule, by two rules, or by a rule and the edge's failsOn. Occurrences follow source order;
     *       unwritten defaults follow explicit values in rule-then-fallback order. Without source, that traversal
     *       orders all occurrences. A type that the fallback repeats counts once, since all its occurrences select
     *       the same fallback. Types are compared by their erasure, never by name, so a subtype is no repeat of its
     *       supertype.</li>
     * </ul>
     *
     * <p>{@code failsOn} is the edge's own failsOn, and {@code failsOnValues} holds, at each position, the value
     * that names the type {@code failsOn} holds there.</p>
     */
    void checkRecoveries(List<RecoveryModel> recoveries, List<TypeMirror> failsOn, List<AnnotationValue> failsOnValues,
            Element element, AnnotationMirror mirror) {
        if (recoveries.isEmpty()) {
            return;
        }
        List<TypeMirror> kinds = new ArrayList<>();
        List<TypeMirror> repeatedKinds = new ArrayList<>();
        List<ThrowableOccurrence> occurrences = new ArrayList<>();
        for (RecoveryModel recovery : recoveries) {
            String kind = binaryName(recovery.kind());
            DiagnosticAnchor kindAnchor = DiagnosticAnchor.of(element, recovery.annotation(), recovery.kindValue());
            if (!contains(kinds, recovery.kind()) && !isFailureKind(recovery.kind())) {
                kindAnchor.report(diagnostics, "SG303", kind + FailureTypes.INVALID_KIND_SUFFIX);
            }
            repeated(recovery.kind(), kinds, repeatedKinds, kindAnchor,
                    "failure kind " + kind + " is declared by more than one recover");
            for (int at = 0; at < recovery.failsOn().size(); at++) {
                TypeMirror type = recovery.failsOn().get(at);
                DiagnosticAnchor anchor = DiagnosticAnchor.of(element, recovery.annotation(),
                        recovery.failsOnValues().get(at));
                if (isForbiddenFailsOn(type)) {
                    anchor.report(diagnostics, "SG302", "recover(" + kind + ", " + recovery.transition()
                            + ") does not allow " + binaryName(type) + "; " + FailureTypes.FORBIDDEN_THROWABLE_RULE);
                }
                occurrences.add(new ThrowableOccurrence(type, anchor, diagnostics.sourceStart(anchor)));
            }
        }
        List<TypeMirror> fallback = new ArrayList<>();
        for (int at = 0; at < failsOn.size(); at++) {
            TypeMirror type = failsOn.get(at);
            if (!contains(fallback, type)) {
                fallback.add(type);
                DiagnosticAnchor anchor = DiagnosticAnchor.of(element, mirror, failsOnValues.get(at));
                occurrences.add(new ThrowableOccurrence(type, anchor, diagnostics.sourceStart(anchor)));
            }
        }
        // Explicit values follow source order; unwritten values retain traversal order after them.
        occurrences.sort(Comparator.comparingLong(
                (ThrowableOccurrence occurrence) -> occurrence.start().orElse(Long.MAX_VALUE)));
        List<TypeMirror> types = new ArrayList<>();
        List<TypeMirror> repeatedTypes = new ArrayList<>();
        for (ThrowableOccurrence occurrence : occurrences) {
            repeated(occurrence.type(), types, repeatedTypes, occurrence.anchor(),
                    binaryName(occurrence.type()) + REPEATED_TYPE);
        }
    }

    /** One temporary throwable declaration; sorting these leaves recovery model order unchanged. */
    private record ThrowableOccurrence(TypeMirror type, DiagnosticAnchor anchor, OptionalLong start) { }

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

    /**
     * SG504 for an instance method of the facade of {@code model}, abstract or default, declared or inherited, that
     * the static {@code graph(...)} of its companion would clash with, as {@link Signatures#clashing} finds it: one
     * named {@code graph} taking what that {@code graph(...)} takes, the definition of each machine it binds as a
     * sub-machine, then one parameter per class with instance members the machine binds. javac would refuse the
     * companion. A method the facade declares, an operation, a check or a default method, is reported where it is
     * declared, and one it only inherits at the facade, with the interface that declares it. This is the facade's
     * counterpart of the refusal of a set's {@code builder} method by {@link SetModelBuilder}.
     */
    void checkGraphFactory(MachineModel model) {
        TypeElement facade = model.types().facade();
        List<TypeMirror> parameters = new ArrayList<>();
        for (MachineTypes child : model.children()) {
            parameters.add(signatures.declared(GRAPH_DEFINITION, child.entity(), child.states(), child.transitions()));
        }
        for (TypeElement dependency : model.instanceDependencies()) {
            parameters.add(dependency.asType());
        }
        List<ExecutableElement> clashing = signatures.clashing(facade, "graph", parameters);
        if (clashing.isEmpty()) {
            return;
        }
        String graph = factory("graph", parameters);
        String takes;
        if (parameters.isEmpty()) {
            takes = "no parameter for a machine without sub-machines and without instance dependencies";
        } else if (model.children().isEmpty()) {
            takes = "every class with instance members the machine binds";
        } else if (model.instanceDependencies().isEmpty()) {
            takes = "the definition of each machine it binds as a sub-machine";
        } else {
            takes = "the definition of each machine it binds as a sub-machine, then every class with instance members"
                    + " the machine binds";
        }
        String companion = companion(model.facadeName());
        for (ExecutableElement method : clashing) {
            if (method.getEnclosingElement().equals(facade)) {
                diagnostics.error("SG504", graph + " is the static " + graph + " " + companion
                        + " declares, which takes " + takes + ": give the method another name", method);
                return;
            }
        }
        diagnostics.error("SG504", facade.getSimpleName() + " inherits " + graph + " from "
                + ((TypeElement) clashing.get(0).getEnclosingElement()).getQualifiedName() + ", but " + companion
                + " declares a static " + graph + ", which takes " + takes + ": give that method another name", facade);
    }

    /** {@code name(A, B)}: the method {@code name} with the simple names of the classes {@code parameters} are. */
    static String factory(String name, List<TypeMirror> parameters) {
        List<String> classes = new ArrayList<>();
        for (TypeMirror parameter : parameters) {
            classes.add(((DeclaredType) parameter).asElement().getSimpleName().toString());
        }
        return name + "(" + String.join(", ", classes) + ")";
    }

    /**
     * The companion of the facade or set named {@code declaration}, as a diagnostic names it: {@code the generated
     * <Name>Runtime}, or {@code the companion of <declaration>} when that name has no PascalCase form, which the
     * processor refuses as {@code SG603} while it checks the declaration all the same.
     */
    static String companion(String declaration) {
        try {
            return "the generated " + SymbolNames.runtimeName(declaration);
        } catch (IllegalArgumentException unnamed) {
            return "the companion of " + declaration;
        }
    }

    /**
     * Whether a failsOn, the edge's own or a recovery rule's, may not name {@code type}. A cancellation is refused
     * with all its subtypes, the two wrappers only as themselves: a subtype of either is matched as it is thrown.
     * {@code IllegalStateException}, the supertype of {@code CancellationException}, stays allowed.
     */
    private boolean isForbiddenFailsOn(TypeMirror type) {
        return signatures.erasureIs(type, "java.lang.Throwable") || signatures.erasureIs(type, "java.lang.Exception")
                || signatures.erasureIs(type, "java.lang.RuntimeException")
                || signatures.subtype(type, "java.lang.Error")
                || signatures.subtype(type, "io.github.graphmaquina.error.StateMachineException")
                || signatures.subtype(type, "java.util.concurrent.CancellationException")
                || signatures.erasureIs(type, "java.util.concurrent.CompletionException")
                || signatures.erasureIs(type, "java.util.concurrent.ExecutionException");
    }

    /**
     * Whether {@code type} is a failure kind: a named class or record, top-level or a member, that is final, so
     * never abstract, implements {@code FailureKind} and is no {@code Throwable}. An interface, an enum and an
     * annotation type are none.
     */
    private boolean isFailureKind(TypeMirror type) {
        if (!(type instanceof DeclaredType declared) || !(declared.asElement() instanceof TypeElement element)) {
            return false;
        }
        boolean named = element.getNestingKind() == NestingKind.TOP_LEVEL
                || element.getNestingKind() == NestingKind.MEMBER;
        boolean classOrRecord = element.getKind() == ElementKind.CLASS || element.getKind() == ElementKind.RECORD;
        return named && classOrRecord && element.getModifiers().contains(Modifier.FINAL)
                && signatures.subtype(type, FAILURE_KIND) && !signatures.subtype(type, "java.lang.Throwable");
    }

    /**
     * SG304 at {@code anchor} when {@code seen} holds {@code type} and {@code reported} does not yet, so that only
     * the second occurrence of a type is reported; {@code type} is seen from then on.
     */
    private void repeated(TypeMirror type, List<TypeMirror> seen, List<TypeMirror> reported, DiagnosticAnchor anchor,
            String message) {
        if (contains(seen, type) && !contains(reported, type)) {
            reported.add(type);
            anchor.report(diagnostics, "SG304", message);
        }
        seen.add(type);
    }

    /** Whether {@code types} holds a type whose erasure is that of {@code type}. */
    private boolean contains(List<TypeMirror> types, TypeMirror type) {
        TypeMirror erased = signatures.types().erasure(type);
        for (TypeMirror held : types) {
            if (signatures.same(signatures.types().erasure(held), erased)) {
                return true;
            }
        }
        return false;
    }

    /** The binary name of the class {@code type} names, as {@code Class.getName()} spells it. */
    private String binaryName(TypeMirror type) {
        return type instanceof DeclaredType declared && declared.asElement() instanceof TypeElement element
                ? signatures.elements().getBinaryName(element).toString()
                : type.toString();
    }
}
