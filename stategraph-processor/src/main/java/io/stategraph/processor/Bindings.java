package io.stategraph.processor;

import io.stategraph.processor.model.Binding;
import io.stategraph.processor.model.MachineTypes;
import java.util.ArrayList;
import java.util.List;
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
            if (fits(candidate, role, machine, peer)) {
                fitting.add(candidate);
            }
        }
        if (fitting.isEmpty()) {
            diagnostics.error("SG505", owner.getSimpleName() + "." + name + " does not have the signature of a " + role.name().toLowerCase()
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
        return Optional.of(new Binding(owner, method, method.getModifiers().contains(Modifier.STATIC), isAsync(method, role)));
    }

    private boolean fits(ExecutableElement method, Role role, MachineTypes machine, Optional<MachineTypes> peer) {
        List<TypeMirror> types = new ArrayList<>();
        for (Element parameter : method.getParameters()) {
            types.add(parameter.asType());
        }
        TypeMirror returned = method.getReturnType();
        return switch (role) {
            case ACTION -> types.size() == 1
                    && signatures.same(types.get(0), signatures.declared("io.stategraph.api.TransitionContext", machine.entity(), machine.states()))
                    && (signatures.same(returned, signatures.declared("io.stategraph.api.ActionOutcome"))
                        || signatures.assignable(returned, signatures.declared("java.util.concurrent.CompletionStage",
                                signatures.declared("io.stategraph.api.ActionOutcome"))));
            case GUARD -> types.size() == 1
                    && signatures.same(types.get(0), signatures.declared("io.stategraph.api.GuardContext", machine.entity(), machine.states()))
                    && (signatures.same(returned, signatures.declared("io.stategraph.api.GuardResult"))
                        || signatures.assignable(returned, signatures.declared("java.util.concurrent.CompletionStage",
                                signatures.declared("io.stategraph.api.GuardResult"))));
            case HOOK -> types.size() == 1 && signatures.same(types.get(0), machine.entity()) && returned.getKind() == TypeKind.VOID;
            case CONDITION -> peer.isPresent() && types.size() == 3
                    && signatures.same(types.get(0), machine.entity())
                    && signatures.assignable(signatures.declared("java.util.Collection", signatures.extendsWildcard(peer.get().entity())), types.get(1))
                    && signatures.same(types.get(2), signatures.declared("io.stategraph.api.PeerView", peer.get().entity(), peer.get().states()))
                    && returned.getKind() == TypeKind.BOOLEAN;
        };
    }

    private boolean isAsync(ExecutableElement method, Role role) {
        if (role != Role.ACTION && role != Role.GUARD) {
            return false;
        }
        return signatures.erasureIs(method.getReturnType(), "java.util.concurrent.CompletionStage")
                || signatures.subtype(method.getReturnType(), "java.util.concurrent.CompletionStage");
    }

    private static String expected(String name, Role role, MachineTypes machine, Optional<MachineTypes> peer) {
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
}
