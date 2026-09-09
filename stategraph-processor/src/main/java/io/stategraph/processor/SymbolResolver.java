package io.stategraph.processor;

import io.stategraph.annotation.internal.GeneratedSymbol;
import io.stategraph.annotation.internal.SymbolKind;
import io.stategraph.codegen.manifest.CompileManifest;
import io.stategraph.codegen.manifest.ExportIndex;
import io.stategraph.codegen.model.MemberDescriptor;
import io.stategraph.codegen.model.SymbolDescriptor;
import io.stategraph.processor.model.Binding;
import io.stategraph.processor.model.MachineTypes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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

    private final Map<String, SymbolDescriptor> symbols;
    private final Signatures signatures;
    private final EffectiveMethods effective;
    private final Bindings bindings;
    private final Diagnostics diagnostics;
    private final Annotations annotations;

    SymbolResolver(Map<String, SymbolDescriptor> symbols, Signatures signatures, EffectiveMethods effective,
            Bindings bindings, Diagnostics diagnostics) {
        this.symbols = symbols;
        this.signatures = signatures;
        this.effective = effective;
        this.bindings = bindings;
        this.diagnostics = diagnostics;
        this.annotations = new Annotations(signatures.elements());
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
     * and {@code SG505} when no exported declaration of that role fits the machine, or more than one does.
     */
    Optional<Binding> resolve(TypeMirror symbol, Bindings.Role role, MachineTypes machine,
            Optional<MachineTypes> peer, DiagnosticAnchor anchor) {
        SymbolKind kind = Bindings.kind(role);
        Optional<Reference> reference = reference(symbol, kind, anchor, symbols, annotations, diagnostics);
        if (reference.isEmpty()) {
            return Optional.empty();
        }
        SymbolDescriptor descriptor = reference.get().descriptor();
        DeclaredType host = reference.get().host();
        List<Binding> fitting = new ArrayList<>();
        for (Binding member : effective.of(host)) {
            if (member.role() != kind || !member.method().getModifiers().contains(Modifier.PUBLIC)
                    || !exported(member, descriptor, (TypeElement) host.asElement())) {
                continue;
            }
            ExecutableType signature = member.effectiveType();
            if (bindings.fits(signature.getParameterTypes(), signature.getReturnType(), role, machine, peer)) {
                fitting.add(member);
            }
        }
        if (fitting.size() != 1) {
            anchor.report(diagnostics, NO_BINDING, describe(descriptor, role, fitting.size())
                    + "; expected " + Bindings.expected(descriptor.member(), role, machine, peer));
            return Optional.empty();
        }
        Binding chosen = fitting.get(0);
        return Optional.of(new Binding(chosen.owner(), chosen.method(), chosen.effectiveType(), kind,
                bindings.arguments(role, machine, peer), chosen.isStatic(), chosen.async()));
    }

    /**
     * The manifest entry {@code symbol} belongs to, or empty after reporting {@code SG605}. A type that
     * carries the generator's own metadata but is not listed by the manifest of this compilation is refused
     * like any other type: the metadata is a claim, and only the manifest makes it one this compilation owns.
     */
    static Optional<Reference> reference(TypeMirror symbol, SymbolKind kind, DiagnosticAnchor anchor,
            Map<String, SymbolDescriptor> symbols, Annotations annotations, Diagnostics diagnostics) {
        if (!(symbol instanceof DeclaredType declared) || !(declared.asElement() instanceof TypeElement element)) {
            anchor.report(diagnostics, UNKNOWN_SYMBOL, symbol + " is not a generated symbol");
            return Optional.empty();
        }
        String name = element.getQualifiedName().toString();
        Optional<AnnotationMirror> metadata = annotations.find(element, GeneratedSymbol.class);
        SymbolDescriptor descriptor = symbols.get(name);
        if (metadata.isEmpty() || descriptor == null) {
            anchor.report(diagnostics, UNKNOWN_SYMBOL, name + " is not a symbol this compilation exports"
                    + " or depends on; use one of the generated <Host>Refs types");
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
     * an override of it that the host inherits. An unexported method that only shares a name and a role with
     * one is not reachable through the symbol.
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

    /** Whether {@code method} overrides the declaration {@code candidate} names, seen from {@code host}. */
    private boolean overrides(ExecutableElement method, MemberDescriptor candidate, TypeElement host) {
        TypeElement declaring = signatures.elements().getTypeElement(candidate.declaringType());
        if (declaring == null) {
            return false;
        }
        for (Element member : declaring.getEnclosedElements()) {
            if (member.getKind() == ElementKind.METHOD && member.getSimpleName().contentEquals(candidate.name())
                    && ((ExecutableElement) member).getParameters().size() == candidate.parameterTypes().size()
                    && signatures.elements().overrides(method, (ExecutableElement) member, host)) {
                return true;
            }
        }
        return false;
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
