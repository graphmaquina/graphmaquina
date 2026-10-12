package io.github.graphmaquina.processor;

import io.github.graphmaquina.annotation.internal.GeneratedSymbol;
import io.github.graphmaquina.annotation.internal.SymbolKind;
import io.github.graphmaquina.codegen.manifest.CompileManifest;
import io.github.graphmaquina.codegen.manifest.ExportIndex;
import io.github.graphmaquina.codegen.model.MemberDescriptor;
import io.github.graphmaquina.codegen.model.SymbolDescriptor;
import io.github.graphmaquina.processor.gen.BoundInvocation;
import io.github.graphmaquina.processor.model.Binding;
import io.github.graphmaquina.processor.model.MachineTypes;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
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
 * Turns a symbol a declaration names into the one method the generated code must call.
 *
 * <p>A symbol names a family: one host and one member name, with every role that family exports. The
 * annotation that uses it fixes the role, and only the exported declarations of that role are considered, so
 * an overload that shares the name without sharing the role is never reachable through it. Among those, the
 * one method whose effective signature suits the machine is selected; none and several are both refused,
 * because the alternative is to depend on the order {@code getAllMembers} happens to return.</p>
 */
final class SymbolResolver {

    private static final String UNKNOWN_SYMBOL = "SG605";
    private static final String NO_BINDING = "SG505";
    private static final String MISSING_INDEX = "SG602";

    private final Map<String, SymbolDescriptor> symbols;
    private final MissingExportIndex missingIndex;
    private final Signatures signatures;
    private final EffectiveMethods effective;
    private final Bindings bindings;
    private final Diagnostics diagnostics;
    private final Annotations annotations;
    private final BoundInvocation invocations;

    SymbolResolver(Map<String, SymbolDescriptor> symbols, MissingExportIndex missingIndex, Signatures signatures,
            EffectiveMethods effective, Bindings bindings, Diagnostics diagnostics) {
        this.symbols = symbols;
        this.missingIndex = missingIndex;
        this.signatures = signatures;
        this.effective = effective;
        this.bindings = bindings;
        this.diagnostics = diagnostics;
        this.annotations = new Annotations(signatures.elements());
        this.invocations = new BoundInvocation(signatures.elements(), signatures.types());
    }

    /** One symbol type this compilation may use: the manifest entry it belongs to and the host it names. */
    record Reference(SymbolDescriptor descriptor, DeclaredType host) { }

    /** Every symbol of the compilation by qualified name, the local exports and the dependencies alike. */
    static Map<String, SymbolDescriptor> symbols(CompileManifest manifest) {
        Map<String, SymbolDescriptor> symbols = new LinkedHashMap<>();
        for (SymbolDescriptor symbol : manifest.local().model().symbols()) {
            symbols.put(symbol.qualifiedName(), symbol);
        }
        for (ExportIndex dependency : manifest.dependencies()) {
            for (SymbolDescriptor symbol : dependency.model().symbols()) {
                symbols.put(symbol.qualifiedName(), symbol);
            }
        }
        return Map.copyOf(symbols);
    }

    /**
     * The method {@code symbol} names when it is used as {@code role} by {@code machine}, or empty after
     * reporting {@code SG605} when the type is not a symbol of this compilation or does not export that role,
     * {@code SG602} when it comes from a dependency that carries no export index, and {@code SG505} when no exported
     * declaration of that role fits the machine, when more than one does, or when the one that fits makes the
     * machine's companion name a type its package cannot access. An instance member of a generic host is never a
     * candidate: the symbol names the host by its raw class, and generated code could only call such a member on a
     * raw parameter, so it is refused as such rather than as a member whose signature does not fit.
     */
    Optional<Binding> resolve(TypeMirror symbol, Bindings.Role role, MachineTypes machine,
            Optional<MachineTypes> peer, DiagnosticAnchor anchor) {
        SymbolKind kind = Bindings.kind(role);
        Optional<Reference> reference = reference(symbol, kind, anchor, symbols, missingIndex, annotations,
                diagnostics);
        if (reference.isEmpty()) {
            return Optional.empty();
        }
        SymbolDescriptor descriptor = reference.get().descriptor();
        DeclaredType host = reference.get().host();
        boolean generic = !((TypeElement) host.asElement()).getTypeParameters().isEmpty();
        boolean genericInstance = false;
        List<Binding> fitting = new ArrayList<>();
        for (Binding member : effective.of(host)) {
            if (member.role() != kind || !member.method().getModifiers().contains(Modifier.PUBLIC)
                    || !exported(member, descriptor, (TypeElement) host.asElement())) {
                continue;
            }
            if (generic && !member.isStatic()) {
                genericInstance = true;
                continue;
            }
            ExecutableType signature = member.effectiveType();
            if (bindings.fits(signature.getParameterTypes(), signature.getReturnType(), role, machine, peer)) {
                fitting.add(member);
            }
        }
        if (fitting.isEmpty() && genericInstance) {
            anchor.report(diagnostics, NO_BINDING, "the exported " + role.name().toLowerCase(Locale.ROOT) + " "
                    + descriptor.member() + " is an instance member of " + descriptor.host() + ", a generic class,"
                    + " which generated code could only declare and call raw: export it from a non-generic subclass"
                    + " marked @GraphBindings, or make it static");
            return Optional.empty();
        }
        if (fitting.size() != 1) {
            anchor.report(diagnostics, NO_BINDING, describe(descriptor, role, fitting.size())
                    + "; expected " + Bindings.expected(descriptor.member(), role, machine, peer));
            return Optional.empty();
        }
        Binding chosen = fitting.get(0);
        Binding selected = new Binding(chosen.owner(), chosen.method(), chosen.effectiveType(), kind,
                bindings.arguments(role, machine, peer, chosen.effectiveType().getParameterTypes().size()), chosen.isStatic(), chosen.async());
        return reachable(selected, descriptor, machine, anchor) ? Optional.of(selected) : Optional.empty();
    }

    /**
     * Whether the companion of {@code machine}, generated in the package of its facade, can name every type the
     * call to {@code binding} makes it write: the host, every class enclosing it, and the parameter types it pins
     * the arguments with. {@code SG505} otherwise, naming each type it cannot access.
     */
    private boolean reachable(Binding binding, SymbolDescriptor descriptor, MachineTypes machine, DiagnosticAnchor anchor) {
        String packageName = Accessibility.packageOf(machine.facade());
        Set<String> inaccessible = new LinkedHashSet<>(Accessibility.inaccessible(binding.owner(), packageName));
        for (TypeMirror cast : invocations.casts(binding)) {
            inaccessible.addAll(Accessibility.inaccessible(cast, packageName));
        }
        if (inaccessible.isEmpty()) {
            return true;
        }
        anchor.report(diagnostics, NO_BINDING, descriptor.qualifiedName() + " makes the companion of "
                + machine.facade().getSimpleName() + " name " + String.join(" and ", inaccessible) + ", which "
                + Accessibility.describe(packageName) + ", where that companion is generated, cannot access: make "
                + (inaccessible.size() == 1 ? "it" : "them") + " public");
        return false;
    }

    /**
     * The manifest entry {@code symbol} belongs to, or empty after reporting {@code SG605}. A type that
     * carries the generator's own metadata but is not listed by the manifest of this compilation is refused
     * like any other type: the metadata is a claim, and only the manifest makes it one this compilation owns.
     * When such a type comes from a dependency that carries no export index, which is what would list it, it is
     * refused as {@code SG602} instead, naming that dependency.
     */
    static Optional<Reference> reference(TypeMirror symbol, SymbolKind kind, DiagnosticAnchor anchor,
            Map<String, SymbolDescriptor> symbols, MissingExportIndex missingIndex, Annotations annotations,
            Diagnostics diagnostics) {
        if (!(symbol instanceof DeclaredType declared) || !(declared.asElement() instanceof TypeElement element)) {
            anchor.report(diagnostics, UNKNOWN_SYMBOL, symbol + " is not a generated symbol");
            return Optional.empty();
        }
        String name = element.getQualifiedName().toString();
        Optional<AnnotationMirror> metadata = annotations.find(element, GeneratedSymbol.class);
        SymbolDescriptor descriptor = symbols.get(name);
        if (metadata.isEmpty() || descriptor == null) {
            Optional<Path> unindexed = metadata.isEmpty() ? Optional.empty() : missingIndex.entryOf(element);
            if (unindexed.isPresent()) {
                anchor.report(diagnostics, MISSING_INDEX, name + MissingExportIndex.unlisted(unindexed.get()));
            } else {
                anchor.report(diagnostics, UNKNOWN_SYMBOL, name + " is not a symbol this compilation exports"
                        + " or depends on; use one of the generated <Host>Refs types");
            }
            return Optional.empty();
        }
        if (!descriptor.kinds().contains(kind)) {
            anchor.report(diagnostics, UNKNOWN_SYMBOL, name + " exports " + kinds(descriptor)
                    + ", not a " + kind.name().toLowerCase(Locale.ROOT));
            return Optional.empty();
        }
        if (!(TypedValues.classLiteral(annotations.value(metadata.get(), "host")) instanceof DeclaredType host)) {
            anchor.report(diagnostics, UNKNOWN_SYMBOL, name + " names no host to call " + descriptor.member() + " on");
            return Optional.empty();
        }
        return Optional.of(new Reference(descriptor, host));
    }

    /**
     * Whether {@code member} is one of the declarations {@code descriptor} exports: the declaration itself, or
     * an override of it that the host declares or inherits. An unexported method that only shares a name and a
     * role with one is not reachable through the symbol.
     */
    private boolean exported(Binding member, SymbolDescriptor descriptor, TypeElement host) {
        for (MemberDescriptor candidate : descriptor.candidates()) {
            if (candidate.role() != member.role() || !candidate.name().equals(member.methodName())
                    || candidate.parameterTypes().size() != member.method().getParameters().size()) {
                continue;
            }
            TypeElement declaring = (TypeElement) member.method().getEnclosingElement();
            if (declaring.getQualifiedName().contentEquals(candidate.declaringType())
                    || overrides(member.method(), candidate, host)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code method} overrides the declaration {@code candidate} names, seen from {@code host}. The declaring
     * type {@code candidate} names is taken among {@code host} and its supertypes, as {@link CanonicalNames#declaring}
     * takes it.
     */
    private boolean overrides(ExecutableElement method, MemberDescriptor candidate, TypeElement host) {
        Predicate<Element> overridden = (Element member) -> member.getKind() == ElementKind.METHOD
                && member.getSimpleName().contentEquals(candidate.name())
                && ((ExecutableElement) member).getParameters().size() == candidate.parameterTypes().size()
                && signatures.elements().overrides(method, (ExecutableElement) member, host);
        TypeElement declaring = CanonicalNames.declaring(signatures.elements(), host, candidate.declaringType(),
                overridden);
        return declaring != null && declaring.getEnclosedElements().stream().anyMatch(overridden);
    }

    private static String describe(SymbolDescriptor descriptor, Bindings.Role role, int fitting) {
        String what = role.name().toLowerCase(Locale.ROOT);
        return fitting == 0
                ? "no exported " + what + " named " + descriptor.member() + " on " + descriptor.host()
                        + " has the signature this machine needs"
                : fitting + " exported " + what + "s named " + descriptor.member() + " on " + descriptor.host()
                        + " fit this machine, so the one to call is ambiguous";
    }

    private static String kinds(SymbolDescriptor descriptor) {
        List<String> names = new ArrayList<>();
        for (SymbolKind kind : descriptor.kinds()) {
            names.add(kind.name().toLowerCase(Locale.ROOT));
        }
        return String.join(", ", names);
    }
}
