package io.github.graphmaquina.processor;

import io.github.graphmaquina.annotation.internal.GeneratedSymbol;
import io.github.graphmaquina.annotation.internal.GeneratedVocabulary;
import io.github.graphmaquina.annotation.internal.SymbolKind;
import io.github.graphmaquina.codegen.ToolVersion;
import io.github.graphmaquina.codegen.manifest.CompileManifest;
import io.github.graphmaquina.codegen.manifest.ExportIndex;
import io.github.graphmaquina.codegen.model.GraphDescriptor;
import io.github.graphmaquina.codegen.model.MemberDescriptor;
import io.github.graphmaquina.codegen.model.PropertyDescriptor;
import io.github.graphmaquina.codegen.model.SymbolDescriptor;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;

/**
 * Matches one manifest against the declarations javac actually has.
 *
 * <p>The manifest was written by a separate tool, from the sources as they were before this compilation
 * started. Every name it carries is therefore a claim, not a fact: a generated annotation must exist and must
 * carry the metadata that says which machine it belongs to, a machine's states must still be the constants of
 * its enum, and a symbol must still name a member that exists. A claim that no longer holds means the
 * generation is stale, and the whole manifest is refused rather than used in part.</p>
 *
 * <p>Existence, identity and the declared signature are checked here. Whether a member's effective signature suits the role it
 * declares is the frontend's work, once the model is being built.</p>
 *
 * <p>Every type a claim names is looked up by its canonical name as {@link CanonicalNames} looks one up: for the
 * package of the declaration the claim was read from, the facade's for its enums and its vocabulary and the host's for
 * its symbol types and the host itself, and among the host and its supertypes for the types that declare its members.
 * The package of a vocabulary annotation or a symbol type, which is that of its facade or its host, is its name without
 * the last two segments.</p>
 */
final class ManifestChecks {

    private static final int SCHEMA_VERSION = 2;

    private ManifestChecks() { }

    /**
     * The first reason {@code manifest} cannot be trusted, phrased to follow the manifest's own path in a
     * diagnostic, or empty when every claim it makes holds.
     */
    static Optional<String> verify(CompileManifest manifest, Elements elements) {
        Optional<String> versions = versions(manifest);
        if (versions.isPresent()) {
            return versions;
        }
        Optional<String> duplicates = duplicates(manifest);
        if (duplicates.isPresent()) {
            return duplicates;
        }
        Annotations annotations = new Annotations(elements);
        for (ExportIndex index : indexes(manifest)) {
            Optional<String> problem = index(index, elements, annotations);
            if (problem.isPresent()) {
                return problem;
            }
        }
        return Optional.empty();
    }

    /** The manifest and every index it carries must be written by this exact tool, for this exact schema. */
    private static Optional<String> versions(CompileManifest manifest) {
        String tool = ToolVersion.current();
        if (manifest.schemaVersion() != SCHEMA_VERSION) {
            return Optional.of("declares schema version " + manifest.schemaVersion()
                + ", but this processor reads version " + SCHEMA_VERSION);
        }
        if (!manifest.toolVersion().equals(tool)) {
            return Optional.of("was written by StateGraph " + manifest.toolVersion()
                + ", but this processor is StateGraph " + tool);
        }
        for (ExportIndex index : indexes(manifest)) {
            if (index.schemaVersion() != SCHEMA_VERSION) {
                return Optional.of("carries the index of " + index.compilationId() + " at schema version "
                    + index.schemaVersion() + ", but this processor reads version " + SCHEMA_VERSION);
            }
            if (!index.toolVersion().equals(tool)) {
                return Optional.of("carries the index of " + index.compilationId() + ", written by StateGraph "
                    + index.toolVersion() + ", but this processor is StateGraph " + tool);
            }
        }
        return Optional.empty();
    }

    /** One name may be claimed once. Two indexes claiming it would make the owner of a declaration ambiguous. */
    private static Optional<String> duplicates(CompileManifest manifest) {
        Set<String> annotationTypes = new HashSet<>();
        Set<String> facades = new HashSet<>();
        Set<String> symbols = new HashSet<>();
        for (ExportIndex index : indexes(manifest)) {
            for (String type : index.annotationTypes()) {
                if (!annotationTypes.add(type)) {
                    return Optional.of("lists the annotation type " + type + " twice");
                }
            }
            for (GraphDescriptor graph : index.model().graphs()) {
                if (!facades.add(graph.facade())) {
                    return Optional.of("describes the machine " + graph.facade() + " twice");
                }
            }
            for (SymbolDescriptor symbol : index.model().symbols()) {
                if (!symbols.add(symbol.qualifiedName())) {
                    return Optional.of("describes the symbol " + symbol.qualifiedName() + " twice");
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> index(ExportIndex index, Elements elements, Annotations annotations) {
        Map<String, GraphDescriptor> graphs = new LinkedHashMap<>();
        for (GraphDescriptor graph : index.model().graphs()) {
            graphs.put(graph.facade(), graph);
            Optional<String> problem = enums(graph, elements);
            if (problem.isPresent()) {
                return problem;
            }
        }
        for (String type : index.annotationTypes()) {
            Optional<String> problem = vocabulary(type, graphs, elements, annotations);
            if (problem.isPresent()) {
                return problem;
            }
        }
        for (SymbolDescriptor symbol : index.model().symbols()) {
            Optional<String> problem = symbol(symbol, elements, annotations);
            if (problem.isPresent()) {
                return problem;
            }
        }
        return Optional.empty();
    }

    /**
     * A machine's states and its transition identifiers are the constants of its two enums, in the order those
     * enums declare them.
     */
    private static Optional<String> enums(GraphDescriptor graph, Elements elements) {
        Optional<String> states = constants(graph, "state", graph.stateType(), graph.states(), elements);
        if (states.isPresent()) {
            return states;
        }
        return constants(graph, "transition", graph.transitionType(), graph.transitions(), elements);
    }

    /** One enum of {@code graph}: it must still be declared, still be an enum, and still declare {@code claimed}. */
    private static Optional<String> constants(GraphDescriptor graph, String role, String type, List<String> claimed,
            Elements elements) {
        TypeElement declaration = CanonicalNames.type(elements, type, graph.packageName());
        if (declaration == null || declaration.getKind() != ElementKind.ENUM) {
            return Optional.of("describes the machine " + graph.facade() + " over the " + role + " enum " + type
                + ", which this compilation does not declare");
        }
        List<String> declared = new ArrayList<>();
        for (Element member : declaration.getEnclosedElements()) {
            if (member.getKind() == ElementKind.ENUM_CONSTANT) {
                declared.add(member.getSimpleName().toString());
            }
        }
        if (!declared.equals(claimed)) {
            return Optional.of("gives " + type + " the " + role + "s " + claimed
                + ", but that enum now declares " + declared);
        }
        return Optional.empty();
    }

    /** A listed annotation type must exist, be an annotation, and say which machine of this index it serves. */
    private static Optional<String> vocabulary(String type, Map<String, GraphDescriptor> graphs,
            Elements elements, Annotations annotations) {
        TypeElement element = CanonicalNames.type(elements, type, CanonicalNames.packageOfMemberType(type));
        if (element == null || element.getKind() != ElementKind.ANNOTATION_TYPE) {
            return Optional.of("lists the annotation type " + type
                + ", which this compilation does not declare as an annotation");
        }
        Optional<AnnotationMirror> metadata = annotations.find(element, GeneratedVocabulary.class);
        if (metadata.isEmpty()) {
            return Optional.of("lists " + type + " as generated vocabulary, but that type carries no"
                + " @GeneratedVocabulary metadata");
        }
        String machine = name(TypedValues.classLiteral(annotations.value(metadata.get(), "machine")));
        GraphDescriptor graph = graphs.get(machine);
        if (graph == null) {
            return Optional.of("lists " + type + ", whose machine " + machine + " it does not describe");
        }
        for (AnnotationValue peer : annotations.array(metadata.get(), "peer")) {
            String name = name(TypedValues.classLiteral(peer));
            if (!graph.peers().contains(name)) {
                return Optional.of("lists " + type + ", whose peer " + name + " is not a peer of " + machine);
            }
        }
        return Optional.empty();
    }

    /** A symbol must exist, name the host and member the manifest gives it, and still resolve to a member. */
    private static Optional<String> symbol(SymbolDescriptor symbol, Elements elements, Annotations annotations) {
        String hostPackage = CanonicalNames.packageOfMemberType(symbol.qualifiedName());
        TypeElement element = CanonicalNames.type(elements, symbol.qualifiedName(), hostPackage);
        if (element == null) {
            return Optional.of("describes the symbol " + symbol.qualifiedName()
                + ", which this compilation does not declare");
        }
        Optional<AnnotationMirror> metadata = annotations.find(element, GeneratedSymbol.class);
        if (metadata.isEmpty()) {
            return Optional.of("describes " + symbol.qualifiedName() + " as a generated symbol, but that type"
                + " carries no @GeneratedSymbol metadata");
        }
        String host = name(TypedValues.classLiteral(annotations.value(metadata.get(), "host")));
        if (!host.equals(symbol.host())) {
            return Optional.of("describes " + symbol.qualifiedName() + " on the host " + symbol.host()
                + ", but that type names " + host);
        }
        String member = annotations.string(metadata.get(), "member");
        if (!member.equals(symbol.member())) {
            return Optional.of("describes " + symbol.qualifiedName() + " as the member " + symbol.member()
                + ", but that type names " + member);
        }
        Set<String> declared = new LinkedHashSet<>();
        for (AnnotationValue kind : annotations.array(metadata.get(), "kinds")) {
            declared.add(TypedValues.enumConstant(kind).getSimpleName().toString());
        }
        if (!declared.equals(names(symbol.kinds()))) {
            return Optional.of("gives " + symbol.qualifiedName() + " the roles " + names(symbol.kinds())
                + ", but that type declares " + declared);
        }
        TypeElement hostType = CanonicalNames.type(elements, symbol.host(), hostPackage);
        if (hostType == null) {
            return Optional.of("describes the symbol " + symbol.qualifiedName() + " on the host " + symbol.host()
                + ", which this compilation does not declare");
        }
        return originals(symbol, hostType, elements);
    }

    /**
     * The original declarations must still have the signatures the generator recorded; {@code host} is the host of
     * {@code symbol}.
     */
    private static Optional<String> originals(SymbolDescriptor symbol, TypeElement host, Elements elements) {
        for (MemberDescriptor candidate : symbol.candidates()) {
            if (!declares(elements, host, candidate.declaringType(), (Element member) -> matches(member, candidate))) {
                return Optional.of("describes " + symbol.qualifiedName() + " over " + candidate.declaringType()
                    + '.' + candidate.name() + candidate.parameterTypes() + " returning " + candidate.returnType()
                    + ", which that type no longer declares with the same signature");
            }
        }
        Optional<PropertyDescriptor> property = symbol.property();
        if (property.isPresent() && !declares(elements, host, property.get().declaringType(), (Element member) ->
                member.getSimpleName().contentEquals(property.get().declarationName()))) {
            return Optional.of("describes " + symbol.qualifiedName() + " over "
                + property.get().declaringType() + '.' + property.get().declarationName()
                + ", which that type no longer declares");
        }
        return Optional.empty();
    }

    /** Compare declaration types, before substituting host arguments or selecting a role's effective overload. */
    private static boolean matches(Element member, MemberDescriptor candidate) {
        if (!member.getSimpleName().contentEquals(candidate.name())
                || member.getModifiers().contains(Modifier.STATIC) != candidate.staticMember()) {
            return false;
        }
        if (member instanceof ExecutableElement method) {
            return method.getParameters().stream()
                    .map((VariableElement parameter) -> ManifestTypes.render(parameter.asType())).toList()
                    .equals(candidate.parameterTypes())
                    && ManifestTypes.render(method.getReturnType()).equals(candidate.returnType());
        }
        return member.getKind() == ElementKind.FIELD && candidate.parameterTypes().isEmpty()
                && ManifestTypes.render(member.asType()).equals(candidate.returnType());
    }

    /**
     * Whether the type {@code declaringType} names declares a member {@code wanted} accepts, that type looked up as the
     * declaring type of such a member of {@code host}.
     */
    private static boolean declares(Elements elements, TypeElement host, String declaringType,
            Predicate<Element> wanted) {
        TypeElement type = CanonicalNames.declaring(elements, host, declaringType, wanted);
        return type != null && type.getEnclosedElements().stream().anyMatch(wanted);
    }

    private static Set<String> names(Set<SymbolKind> kinds) {
        Set<String> names = new LinkedHashSet<>();
        for (SymbolKind kind : kinds) {
            names.add(kind.name());
        }
        return names;
    }

    /** The local index first, then the ones the classpath publishes, so a local claim is reported first. */
    private static List<ExportIndex> indexes(CompileManifest manifest) {
        List<ExportIndex> indexes = new ArrayList<>();
        indexes.add(manifest.local());
        indexes.addAll(manifest.dependencies());
        return indexes;
    }

    /** The canonical name of a type an annotation value names. */
    private static String name(TypeMirror type) {
        if (type instanceof DeclaredType declared && declared.asElement() instanceof TypeElement element) {
            return element.getQualifiedName().toString();
        }
        return type.toString();
    }
}
