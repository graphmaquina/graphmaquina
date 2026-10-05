package io.github.graphmaquina.processor;

import io.github.graphmaquina.annotation.GraphAction;
import io.github.graphmaquina.annotation.GraphCondition;
import io.github.graphmaquina.annotation.GraphGuard;
import io.github.graphmaquina.annotation.GraphHook;
import io.github.graphmaquina.annotation.internal.SymbolKind;
import io.github.graphmaquina.processor.model.Binding;
import java.lang.annotation.Annotation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;

/**
 * The methods a host really has, as javac sees them: one entry per declaration that is still reachable after
 * overriding and carries a role, each with that role and with the signature it has in that host.
 *
 * <p>A role is part of the contract a marker declares, so an override that spells no marker of its own keeps
 * the role of the declaration it overrides; {@link javax.lang.model.util.Elements#overrides} decides that, not
 * a name and an arity. Two overloads of one name never lend each other a role. The signature is the one
 * {@code Types.asMemberOf} gives in the host, so a contract inherited from a generic base is read with the
 * host's own arguments substituted for the base's type variables.</p>
 */
final class EffectiveMethods {

    private static final Map<Class<? extends Annotation>, SymbolKind> ROLES = roles();
    private static final String COMPLETION_STAGE = "java.util.concurrent.CompletionStage";

    private final Signatures signatures;
    private final Annotations annotations;

    EffectiveMethods(Signatures signatures) {
        this.signatures = signatures;
        this.annotations = new Annotations(signatures.elements());
    }

    /** The role one declaration spells, together with the marker that spells it, for anchoring a diagnostic. */
    record Marker(SymbolKind role, AnnotationMirror mirror) { }

    /**
     * Every method of {@code host} that carries a role, its own or the one it inherits from the contract it
     * overrides, with the signature it has in this host. Methods without a role are left out: they are not
     * exported, so nothing may bind to them.
     */
    List<Binding> of(DeclaredType host) {
        TypeElement element = (TypeElement) host.asElement();
        List<Binding> effective = new ArrayList<>();
        for (Element member : signatures.elements().getAllMembers(element)) {
            if (member.getKind() != ElementKind.METHOD) {
                continue;
            }
            ExecutableElement method = (ExecutableElement) member;
            Optional<SymbolKind> role = role(method, element);
            if (role.isEmpty()) {
                continue;
            }
            ExecutableType effectiveType = signatures.asMemberOf(host, method);
            effective.add(new Binding(element, method, effectiveType, role.get(), List.of(),
                    method.getModifiers().contains(Modifier.STATIC),
                    async(role.get(), effectiveType.getReturnType())));
        }
        return List.copyOf(effective);
    }

    /** The role {@code method} declares itself, or empty when it declares none. */
    Optional<Marker> marker(ExecutableElement method) {
        for (Map.Entry<Class<? extends Annotation>, SymbolKind> role : ROLES.entrySet()) {
            Optional<AnnotationMirror> mirror = annotations.find(method, role.getKey());
            if (mirror.isPresent()) {
                return Optional.of(new Marker(role.getValue(), mirror.get()));
            }
        }
        return Optional.empty();
    }

    /** The marked declarations {@code method} overrides, seen from {@code host}, nearest supertype first. */
    List<ExecutableElement> overridden(ExecutableElement method, TypeElement host) {
        List<ExecutableElement> contracts = new ArrayList<>();
        for (TypeElement supertype : supertypes(host)) {
            for (Element member : supertype.getEnclosedElements()) {
                if (member.getKind() != ElementKind.METHOD) {
                    continue;
                }
                ExecutableElement contract = (ExecutableElement) member;
                if (contract.getSimpleName().contentEquals(method.getSimpleName()) && marker(contract).isPresent()
                        && signatures.elements().overrides(method, contract, host)) {
                    contracts.add(contract);
                }
            }
        }
        return contracts;
    }

    /** The role of {@code method} in {@code host}: the one it declares, or the one its contract declares. */
    private Optional<SymbolKind> role(ExecutableElement method, TypeElement host) {
        Optional<Marker> own = marker(method);
        if (own.isPresent()) {
            return Optional.of(own.get().role());
        }
        List<ExecutableElement> contracts = overridden(method, host);
        return contracts.isEmpty() ? Optional.empty() : marker(contracts.get(0)).map(Marker::role);
    }

    /** Only an action or a guard may answer later, and only when its result is a completion stage. */
    private boolean async(SymbolKind role, TypeMirror returned) {
        if (role != SymbolKind.ACTION && role != SymbolKind.GUARD) {
            return false;
        }
        return signatures.erasureIs(returned, COMPLETION_STAGE) || signatures.subtype(returned, COMPLETION_STAGE);
    }

    /** Every supertype of {@code host}, transitively, each one once. */
    private List<TypeElement> supertypes(TypeElement host) {
        List<TypeElement> found = new ArrayList<>();
        Set<TypeElement> visited = new LinkedHashSet<>();
        Deque<TypeMirror> pending = new ArrayDeque<>(signatures.types().directSupertypes(host.asType()));
        while (!pending.isEmpty()) {
            TypeMirror current = pending.removeFirst();
            if (!(current instanceof DeclaredType declared) || !(declared.asElement() instanceof TypeElement element)) {
                continue;
            }
            if (!visited.add(element)) {
                continue;
            }
            found.add(element);
            pending.addAll(signatures.types().directSupertypes(current));
        }
        return found;
    }

    private static Map<Class<? extends Annotation>, SymbolKind> roles() {
        Map<Class<? extends Annotation>, SymbolKind> roles = new LinkedHashMap<>();
        roles.put(GraphAction.class, SymbolKind.ACTION);
        roles.put(GraphGuard.class, SymbolKind.GUARD);
        roles.put(GraphHook.class, SymbolKind.HOOK);
        roles.put(GraphCondition.class, SymbolKind.CONDITION);
        return Collections.unmodifiableMap(roles);
    }
}
