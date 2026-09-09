package io.stategraph.processor;

import io.stategraph.annotation.internal.SymbolKind;
import io.stategraph.processor.model.Binding;
import io.stategraph.processor.model.MachineTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import lombok.RequiredArgsConstructor;

/** Resolves the methods of a by class that a cell or a watch references, checking the exact signature for the role. */
@RequiredArgsConstructor
final class Bindings {

    /** What the method is used for; it fixes the expected signature. */
    enum Role { ACTION, GUARD, HOOK, CONDITION }

    private static final String TRANSITION_CONTEXT = "io.stategraph.api.TransitionContext";
    private static final String GUARD_CONTEXT = "io.stategraph.api.GuardContext";
    private static final String ACTION_OUTCOME = "io.stategraph.api.ActionOutcome";
    private static final String GUARD_RESULT = "io.stategraph.api.GuardResult";
    private static final String PEER_VIEW = "io.stategraph.api.PeerView";
    private static final String COLLECTION = "java.util.Collection";
    private static final String COMPLETION_STAGE = "java.util.concurrent.CompletionStage";

    private final Signatures signatures;
    private final Diagnostics diagnostics;

    /** The binding, or empty after reporting SG505. {@code peer} is only needed for CONDITION. */
    Optional<Binding> resolve(TypeMirror ownerType, String name, Role role, MachineTypes machine, Optional<MachineTypes> peer,
                              Element element, AnnotationMirror mirror, AnnotationValue anchor) {
        if (!(ownerType instanceof DeclaredType declared)) {
            diagnostics.error("SG505", "by must be a class, not " + ownerType, element, mirror, anchor);
            return Optional.empty();
        }
        TypeElement owner = (TypeElement) declared.asElement();
        if (!owner.getModifiers().contains(Modifier.PUBLIC)) {
            diagnostics.error("SG505", owner.getQualifiedName() + " must be public: the generated code calls it from " + machine.facade().getSimpleName()
                    + "'s package", element, mirror, anchor);
            return Optional.empty();
        }
        List<ExecutableElement> named = new ArrayList<>();
        for (Element member : signatures.elements().getAllMembers(owner)) {
            if (member.getKind() == ElementKind.METHOD && member.getSimpleName().contentEquals(name)
                    && !((TypeElement) member.getEnclosingElement()).getQualifiedName().contentEquals("java.lang.Object")) {
                named.add((ExecutableElement) member);
            }
        }
        if (named.isEmpty()) {
            diagnostics.error("SG505", owner.getSimpleName() + " has no method named '" + name + "'; expected " + expected(name, role, machine, peer),
                    element, mirror, anchor);
            return Optional.empty();
        }
        List<ExecutableElement> fitting = new ArrayList<>();
        for (ExecutableElement candidate : named) {
            if (fits(parameterTypes(candidate), candidate.getReturnType(), role, machine, peer)) {
                fitting.add(candidate);
            }
        }
        if (fitting.isEmpty()) {
            diagnostics.error("SG505", owner.getSimpleName() + "." + name + " does not have the signature of a " + role.name().toLowerCase(Locale.ROOT)
                    + "; expected " + expected(name, role, machine, peer), element, mirror, anchor);
            return Optional.empty();
        }
        if (fitting.size() > 1) {
            diagnostics.error("SG505", owner.getSimpleName() + "." + name + " is ambiguous: " + fitting.size() + " overloads fit", element, mirror, anchor);
            return Optional.empty();
        }
        ExecutableElement method = fitting.get(0);
        if (!method.getModifiers().contains(Modifier.PUBLIC)) {
            diagnostics.error("SG505", owner.getSimpleName() + "." + name + " must be public", element, mirror, anchor);
            return Optional.empty();
        }
        return Optional.of(new Binding(owner, method, signatures.asMemberOf(declared, method), kind(role),
                arguments(role, machine, peer), method.getModifiers().contains(Modifier.STATIC),
                isAsync(method.getReturnType(), role)));
    }

    /**
     * Whether a method taking {@code parameters} and returning {@code returned} is usable as {@code role} of
     * {@code machine}. The types are the ones the method has where it is called, so a contract of a generic
     * base is judged with the host's own entity and states.
     */
    boolean fits(List<? extends TypeMirror> parameters, TypeMirror returned, Role role, MachineTypes machine, Optional<MachineTypes> peer) {
        return switch (role) {
            case ACTION -> parameters.size() == 1
                    && signatures.same(parameters.get(0), signatures.declared(TRANSITION_CONTEXT, machine.entity(), machine.states()))
                    && (signatures.same(returned, signatures.declared(ACTION_OUTCOME))
                        || signatures.assignable(returned, signatures.declared(COMPLETION_STAGE, signatures.declared(ACTION_OUTCOME))));
            case GUARD -> parameters.size() == 1
                    && signatures.same(parameters.get(0), signatures.declared(GUARD_CONTEXT, machine.entity(), machine.states()))
                    && (signatures.same(returned, signatures.declared(GUARD_RESULT))
                        || signatures.assignable(returned, signatures.declared(COMPLETION_STAGE, signatures.declared(GUARD_RESULT))));
            case HOOK -> parameters.size() == 1 && signatures.same(parameters.get(0), machine.entity()) && returned.getKind() == TypeKind.VOID;
            case CONDITION -> peer.isPresent() && parameters.size() == 3
                    && signatures.same(parameters.get(0), machine.entity())
                    && signatures.assignable(signatures.declared(COLLECTION, signatures.extendsWildcard(peer.get().entity())), parameters.get(1))
                    && signatures.same(parameters.get(2), signatures.declared(PEER_VIEW, peer.get().entity(), peer.get().states()))
                    && returned.getKind() == TypeKind.BOOLEAN;
        };
    }

    /**
     * The static types of the arguments the generated code passes to a {@code role} of {@code machine}: the
     * parameters of the SPI's own functional interface, which the emitter compares the selected method's
     * parameters against.
     */
    List<TypeMirror> arguments(Role role, MachineTypes machine, Optional<MachineTypes> peer) {
        return switch (role) {
            case ACTION -> List.of(signatures.declared(TRANSITION_CONTEXT, machine.entity(), machine.states()));
            case GUARD -> List.of(signatures.declared(GUARD_CONTEXT, machine.entity(), machine.states()));
            case HOOK -> List.of(machine.entity());
            case CONDITION -> peer.<List<TypeMirror>>map((MachineTypes found) -> List.of(
                    machine.entity(),
                    signatures.declared(COLLECTION, signatures.extendsWildcard(found.entity())),
                    signatures.declared(PEER_VIEW, found.entity(), found.states()))).orElseGet(List::of);
        };
    }

    /** Whether the call is asynchronous, which only an action or a guard can be. */
    boolean isAsync(TypeMirror returned, Role role) {
        if (role != Role.ACTION && role != Role.GUARD) {
            return false;
        }
        return signatures.erasureIs(returned, COMPLETION_STAGE) || signatures.subtype(returned, COMPLETION_STAGE);
    }

    /** The exported category a role is served by; one symbol may export several of them. */
    static SymbolKind kind(Role role) {
        return switch (role) {
            case ACTION -> SymbolKind.ACTION;
            case GUARD -> SymbolKind.GUARD;
            case HOOK -> SymbolKind.HOOK;
            case CONDITION -> SymbolKind.CONDITION;
        };
    }

    static String expected(String name, Role role, MachineTypes machine, Optional<MachineTypes> peer) {
        String e = machine.entityName();
        String s = machine.statesName();
        return switch (role) {
            case ACTION -> "ActionOutcome " + name + "(TransitionContext<" + e + ", " + s + ">) or CompletionStage<ActionOutcome> " + name
                    + "(TransitionContext<" + e + ", " + s + ">)";
            case GUARD -> "GuardResult " + name + "(GuardContext<" + e + ", " + s + ">) or CompletionStage<GuardResult> " + name
                    + "(GuardContext<" + e + ", " + s + ">)";
            case HOOK -> "void " + name + "(" + e + ")";
            case CONDITION -> "boolean " + name + "(" + e + ", Collection<? extends " + peer.map(MachineTypes::entityName).orElse("P") + ">, PeerView<"
                    + peer.map(MachineTypes::entityName).orElse("P") + ", " + peer.map(MachineTypes::statesName).orElse("Q") + ">)";
        };
    }

    private static List<TypeMirror> parameterTypes(ExecutableElement method) {
        List<TypeMirror> types = new ArrayList<>();
        for (Element parameter : method.getParameters()) {
            types.add(parameter.asType());
        }
        return types;
    }
}
