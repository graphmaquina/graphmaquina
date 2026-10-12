package io.github.graphmaquina.processor;

import io.github.graphmaquina.annotation.GraphProperty;
import io.github.graphmaquina.annotation.internal.SymbolKind;
import io.github.graphmaquina.codegen.manifest.CompileManifest;
import io.github.graphmaquina.codegen.model.MemberDescriptor;
import io.github.graphmaquina.codegen.model.PropertyDescriptor;
import io.github.graphmaquina.codegen.model.SymbolDescriptor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;

/**
 * Checks every member this compilation exports against the declarations javac has, whether or not a graph of
 * this compilation uses one.
 *
 * <p>An export is published: a dependent compilation reads it from the manifest and binds to it without seeing
 * these sources again. So the contract is verified where it is declared, not where it is used. What is
 * verified here is what a use cannot repair: a marked method is public, since generated code may call it from
 * another package, its shape is the one its role requires, it does not contradict the role of the contract it
 * overrides, and a static one is inherited by the host that exports it, since generated code calls it through that
 * host; a property is still declared as the field or the getter it was read from, and such a getter is a
 * public instance method. Whether its host is accessible where a companion is generated, and which entity and
 * which states it suits, are questions about a machine, and belong to the use.</p>
 *
 * <p>The host of an export is looked up by the canonical name the manifest gives it, as {@link CanonicalNames} looks
 * one up for the package of the host, which is the name of the export's symbol type without its last two segments.
 * The type that declares each of its members is taken among the host and its supertypes, as
 * {@link CanonicalNames#declaring} takes it.</p>
 */
final class ExportValidator {

    private static final String INVALID = "SG604";
    private static final String TRANSITION_CONTEXT = "io.github.graphmaquina.api.TransitionContext";
    private static final String COMMIT_CONTEXT = "io.github.graphmaquina.api.CommitContext";
    private static final String GUARD_CONTEXT = "io.github.graphmaquina.api.GuardContext";
    private static final String ACTION_OUTCOME = "io.github.graphmaquina.api.ActionOutcome";
    private static final String GUARD_RESULT = "io.github.graphmaquina.api.GuardResult";
    private static final String PEER_VIEW = "io.github.graphmaquina.api.PeerView";
    private static final String COLLECTION = "java.util.Collection";
    private static final String COMPLETION_STAGE = "java.util.concurrent.CompletionStage";
    private static final String OPTIONAL = "java.util.Optional";

    private final Signatures signatures;
    private final EffectiveMethods effective;
    private final Diagnostics diagnostics;
    private final Annotations annotations;

    ExportValidator(Signatures signatures, EffectiveMethods effective, Diagnostics diagnostics) {
        this.signatures = signatures;
        this.effective = effective;
        this.diagnostics = diagnostics;
        this.annotations = new Annotations(signatures.elements());
    }

    /**
     * The qualified names of the local exports of {@code manifest} that do not hold, each reported as SG604 where it
     * is declared; empty when every one holds. A declaration two exports reach is reported once, and makes both
     * of them exports that do not hold.
     */
    Set<String> validate(CompileManifest manifest) {
        Set<String> invalid = new LinkedHashSet<>();
        Map<ExecutableElement, Boolean> validated = new LinkedHashMap<>();
        for (SymbolDescriptor symbol : manifest.local().model().symbols()) {
            int before = diagnostics.errors();
            boolean holds = true;
            TypeElement host = CanonicalNames.type(signatures.elements(), symbol.host(),
                    CanonicalNames.packageOfMemberType(symbol.qualifiedName()));
            if (host == null) {
                diagnostics.error(INVALID, symbol.qualifiedName() + " exports members of " + symbol.host()
                        + ", which this compilation does not declare");
                invalid.add(symbol.qualifiedName());
                continue;
            }
            symbol.property().ifPresent((PropertyDescriptor property) -> property(symbol, property, host));
            for (MemberDescriptor candidate : symbol.candidates()) {
                holds &= candidate(symbol, candidate, host, validated);
            }
            if (!holds || diagnostics.errors() > before) {
                invalid.add(symbol.qualifiedName());
            }
        }
        return invalid;
    }

    /**
     * Whether every marked declaration one candidate of {@code host} names holds, each one checked once however many
     * candidates reach it; {@code validated} remembers what each declaration checked already came to.
     */
    private boolean candidate(SymbolDescriptor symbol, MemberDescriptor candidate, TypeElement host,
            Map<ExecutableElement, Boolean> validated) {
        TypeElement declaring = CanonicalNames.declaring(signatures.elements(), host, candidate.declaringType(),
                (Element member) -> marks(member, candidate)
                        && effective.marker((ExecutableElement) member).orElseThrow().role() == candidate.role());
        if (declaring == null) {
            diagnostics.error(INVALID, symbol.qualifiedName() + " exports " + candidate.declaringType() + '.'
                    + candidate.name() + ", whose declaring type this compilation does not declare");
            return false;
        }
        List<ExecutableElement> marked = marked(declaring, candidate);
        if (marked.isEmpty()) {
            diagnostics.error(INVALID, symbol.qualifiedName() + " exports " + candidate.declaringType() + '.'
                    + candidate.name() + " with " + candidate.parameterTypes().size()
                    + " parameters, which that type no longer declares as a graph member", declaring);
            return false;
        }
        boolean declared = false;
        boolean holds = true;
        for (ExecutableElement method : marked) {
            SymbolKind role = effective.marker(method).orElseThrow().role();
            declared |= role == candidate.role();
            Optional<String> uninherited = notInherited(method, declaring, host);
            if (uninherited.isPresent()) {
                DiagnosticAnchor.of(method, effective.marker(method).orElseThrow().mirror()).report(diagnostics,
                        INVALID, symbol.qualifiedName() + " exports " + declaring.getQualifiedName() + '.'
                        + method.getSimpleName() + " through " + host.getQualifiedName()
                        + ", which does not inherit it: " + uninherited.get());
                holds = false;
            }
            if (!validated.containsKey(method)) {
                validated.put(method, member(method, declaring, role));
            }
            holds &= validated.get(method);
        }
        if (!declared) {
            diagnostics.error(INVALID, symbol.qualifiedName() + " exports " + candidate.declaringType() + '.'
                    + candidate.name() + " as " + name(candidate.role()) + ", which it no longer declares",
                    declaring);
            return false;
        }
        return holds;
    }

    /**
     * Why {@code host} does not inherit the static method {@code method} of its supertype {@code declaring}, which
     * generated code calls through {@code host}: neither a class nor an interface inherits a static method of an
     * interface, and a method {@code host} declares or inherits may hide it. Empty when {@code host} inherits it, when
     * {@code host} declares it, and for an instance method, which is inherited or overridden by one that keeps its
     * role.
     */
    private Optional<String> notInherited(ExecutableElement method, TypeElement declaring, TypeElement host) {
        if (declaring.equals(host) || !method.getModifiers().contains(Modifier.STATIC)) {
            return Optional.empty();
        }
        if (declaring.getKind().isInterface()) {
            return Optional.of("neither a class nor an interface inherits a static method of an interface");
        }
        for (ExecutableElement other : ElementFilter.methodsIn(signatures.elements().getAllMembers(host))) {
            if (signatures.elements().hides(other, method)) {
                return Optional.of(((TypeElement) other.getEnclosingElement()).getQualifiedName() + "."
                        + other.getSimpleName() + " hides it");
            }
        }
        return Optional.empty();
    }

    /** Whether one marked declaration holds: public, shaped like its role, and true to its contract. */
    private boolean member(ExecutableElement method, TypeElement declaring, SymbolKind role) {
        int before = diagnostics.errors();
        DiagnosticAnchor anchor = DiagnosticAnchor.of(method, effective.marker(method).orElseThrow().mirror());
        String name = declaring.getQualifiedName() + "." + method.getSimpleName();
        if (!method.getModifiers().contains(Modifier.PUBLIC)) {
            anchor.report(diagnostics, INVALID, name + " is exported as " + name(role)
                    + ", so it must be public: generated code calls it from another package");
        }
        if (!structure(method, role)) {
            anchor.report(diagnostics, INVALID, name + " does not have the signature of " + name(role)
                    + ": expected " + shape(role));
        }
        for (ExecutableElement contract : effective.overridden(method, declaring)) {
            SymbolKind inherited = effective.marker(contract).orElseThrow().role();
            if (inherited != role) {
                anchor.report(diagnostics, INVALID, name + " is exported as " + name(role) + ", but it overrides "
                        + ((TypeElement) contract.getEnclosingElement()).getQualifiedName() + '.'
                        + contract.getSimpleName() + ", whose contract is " + name(inherited));
            }
        }
        return diagnostics.errors() == before;
    }

    /** The declaration a property was resolved from must still be the kind of declaration it was read as. */
    private void property(SymbolDescriptor symbol, PropertyDescriptor property, TypeElement host) {
        TypeElement declaring = CanonicalNames.declaring(signatures.elements(), host, property.declaringType(),
                (Element member) -> reads(member, property)
                        && annotations.find(member, GraphProperty.class).isPresent());
        if (declaring == null) {
            diagnostics.error(INVALID, symbol.qualifiedName() + " exports the property " + property.logicalName()
                    + " of " + property.declaringType() + ", which this compilation does not declare", host);
            return;
        }
        for (Element member : declaring.getEnclosedElements()) {
            if (reads(member, property)) {
                if (property.directGetter()) {
                    reader((ExecutableElement) member, declaring, property);
                }
                return;
            }
        }
        diagnostics.error(INVALID, symbol.qualifiedName() + " exports the property " + property.logicalName()
                + " of " + property.declaringType() + ", which no longer declares "
                + (property.directGetter() ? "a getter " : "a field ") + property.declarationName(), declaring);
    }

    /**
     * A marked getter is the reader itself, and the generated code calls it on an entity from another package.
     * One that is not public, or that is static and so is not called on an entity at all, is refused where it
     * is declared: no other accessor stands in for a getter a marker named.
     */
    private void reader(ExecutableElement getter, TypeElement declaring, PropertyDescriptor property) {
        if (Accessors.isPublicInstance(getter)) {
            return;
        }
        DiagnosticAnchor anchor = annotations.find(getter, GraphProperty.class)
                .map((AnnotationMirror marker) -> DiagnosticAnchor.of(getter, marker))
                .orElseGet(() -> DiagnosticAnchor.of(getter));
        anchor.report(diagnostics, INVALID, declaring.getQualifiedName() + "." + getter.getSimpleName()
                + " exports the property " + property.logicalName()
                + ", so it must be a public instance method: generated code calls it on the entity");
    }

    /**
     * Whether {@code method} is shaped like its role, judged on the declaration alone. A generic base declares
     * its contexts over its own type variables and has no entity to compare them with, so what is required
     * here is the shape: which context, how many parameters, what kind of result.
     */
    private boolean structure(ExecutableElement method, SymbolKind role) {
        List<? extends Element> parameters = method.getParameters();
        TypeMirror returned = method.getReturnType();
        return switch (role) {
            case ACTION -> parameters.size() == 1
                    && signatures.erasureIs(parameters.get(0).asType(), TRANSITION_CONTEXT)
                    && result(returned, ACTION_OUTCOME);
            case GUARD -> parameters.size() == 1
                    && signatures.erasureIs(parameters.get(0).asType(), GUARD_CONTEXT)
                    && result(returned, GUARD_RESULT);
            case HOOK -> (parameters.size() == 1 || (parameters.size() == 2
                    && signatures.erasureIs(parameters.get(1).asType(), COMMIT_CONTEXT)))
                    && returned.getKind() == TypeKind.VOID;
            case CONDITION -> parameters.size() == 3 && returned.getKind() == TypeKind.BOOLEAN
                    && signatures.erasureAssignable(signatures.declared(COLLECTION), parameters.get(1).asType())
                    && signatures.erasureIs(parameters.get(2).asType(), PEER_VIEW);
            case PROPERTY -> false;
            case LOOKUP -> parameters.size() == 1 && signatures.erasureIs(returned, OPTIONAL);
        };
    }

    /** A result the engine understands: the verdict itself, or the completion stage that will carry it. */
    private boolean result(TypeMirror returned, String verdict) {
        return signatures.same(returned, signatures.declared(verdict))
                || signatures.assignable(returned, signatures.declared(COMPLETION_STAGE, signatures.declared(verdict)));
    }

    /** The declarations of {@code declaring} that {@code candidate} names and that carry a role of their own. */
    private List<ExecutableElement> marked(TypeElement declaring, MemberDescriptor candidate) {
        List<ExecutableElement> found = new ArrayList<>();
        for (Element member : declaring.getEnclosedElements()) {
            if (marks(member, candidate)) {
                found.add((ExecutableElement) member);
            }
        }
        return found;
    }

    /** Whether {@code member} is a method of the name and the arity of {@code candidate} that carries a marker. */
    private boolean marks(Element member, MemberDescriptor candidate) {
        return member.getKind() == ElementKind.METHOD && member.getSimpleName().contentEquals(candidate.name())
                && ((ExecutableElement) member).getParameters().size() == candidate.parameterTypes().size()
                && effective.marker((ExecutableElement) member).isPresent();
    }

    /** Whether {@code member} has the name and the kind, getter or field, of the declaration {@code property} names. */
    private static boolean reads(Element member, PropertyDescriptor property) {
        return member.getSimpleName().contentEquals(property.declarationName()) && (property.directGetter()
                ? member.getKind() == ElementKind.METHOD && ((ExecutableElement) member).getParameters().isEmpty()
                : member.getKind() == ElementKind.FIELD);
    }

    private static String shape(SymbolKind role) {
        return switch (role) {
            case ACTION -> "ActionOutcome or CompletionStage<ActionOutcome> from one TransitionContext";
            case GUARD -> "GuardResult or CompletionStage<GuardResult> from one GuardContext";
            case HOOK -> "void from the entity alone or the entity and a CommitContext";
            case CONDITION -> "boolean from the entity, a supertype of Collection and a PeerView";
            case PROPERTY -> "a field or a getter";
            case LOOKUP -> "Optional of the parent entity from the child entity alone";
        };
    }

    private static String name(SymbolKind role) {
        String spelled = role.name().toLowerCase(Locale.ROOT);
        return (role == SymbolKind.ACTION ? "an " : "a ") + spelled;
    }
}
