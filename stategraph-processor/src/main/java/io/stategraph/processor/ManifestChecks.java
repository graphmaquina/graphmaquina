package io.stategraph.processor;

import io.stategraph.annotation.internal.GeneratedSymbol;
import io.stategraph.annotation.internal.GeneratedVocabulary;
import io.stategraph.annotation.internal.SymbolKind;
import io.stategraph.codegen.ToolVersion;
import io.stategraph.codegen.manifest.CompileManifest;
import io.stategraph.codegen.manifest.ExportIndex;
import io.stategraph.codegen.model.GraphDescriptor;
import io.stategraph.codegen.model.MemberDescriptor;
import io.stategraph.codegen.model.PropertyDescriptor;
import io.stategraph.codegen.model.SymbolDescriptor;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
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
 * <p>Only existence and identity are checked here. Whether a member's effective signature suits the role it
 * declares is the frontend's work, once the model is being built.</p>
 */
final class ManifestChecks {

    private static final int SCHEMA_VERSION = 1;

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
            Optional<String> problem = states(graph, elements);
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

    /** A machine's states are the constants of its enum, in the order that enum declares them. */
    private static Optional<String> states(GraphDescriptor graph, Elements elements) {
        TypeElement state = elements.getTypeElement(graph.stateType());
        if (state == null || state.getKind() != ElementKind.ENUM) {
            return Optional.of("describes the machine " + graph.facade() + " over the state enum "
                + graph.stateType() + ", which this compilation does not declare");
        }
        List<String> constants = new ArrayList<>();
        for (Element member : state.getEnclosedElements()) {
            if (member.getKind() == ElementKind.ENUM_CONSTANT) {
                constants.add(member.getSimpleName().toString());
            }
        }
        if (!constants.equals(graph.states())) {
            return Optional.of("gives " + graph.stateType() + " the states " + graph.states()
                + ", but that enum now declares " + constants);
        }
        return Optional.empty();
    }

    /** A listed annotation type must exist, be an annotation, and say which machine of this index it serves. */
    private static Optional<String> vocabulary(String type, Map<String, GraphDescriptor> graphs,
            Elements elements, Annotations annotations) {
        TypeElement element = elements.getTypeElement(type);
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
        TypeElement element = elements.getTypeElement(symbol.qualifiedName());
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
        if (elements.getTypeElement(symbol.host()) == null) {
            return Optional.of("describes the symbol " + symbol.qualifiedName() + " on the host " + symbol.host()
                + ", which this compilation does not declare");
        }
        return originals(symbol, elements);
    }

    /** The original declarations a symbol was resolved from must still exist under the same name and arity. */
    private static Optional<String> originals(SymbolDescriptor symbol, Elements elements) {
        for (MemberDescriptor candidate : symbol.candidates()) {
            if (!declares(elements, candidate.declaringType(), candidate.name(),
                    candidate.parameterTypes().size())) {
                return Optional.of("describes " + symbol.qualifiedName() + " over " + candidate.declaringType()
                    + '.' + candidate.name() + " with " + candidate.parameterTypes().size()
                    + " parameters, which that type no longer declares");
            }
        }
        Optional<PropertyDescriptor> property = symbol.property();
        if (property.isPresent() && members(elements, property.get().declaringType(),
                property.get().declarationName()).isEmpty()) {
            return Optional.of("describes " + symbol.qualifiedName() + " over "
                + property.get().declaringType() + '.' + property.get().declarationName()
                + ", which that type no longer declares");
        }
        return Optional.empty();
    }

    /**
     * Whether {@code declaringType} declares a member named {@code name} taking {@code arity} parameters, a
     * field counting as none. Only the name and the arity are matched: which overload a role actually resolves
     * to is decided against the effective signature, when the model is built.
     */
    private static boolean declares(Elements elements, String declaringType, String name, int arity) {
        for (Element member : members(elements, declaringType, name)) {
            if (member instanceof ExecutableElement method && method.getParameters().size() == arity) {
                return true;
            }
            if (member.getKind() == ElementKind.FIELD && arity == 0) {
                return true;
            }
        }
        return false;
    }

    /** The members {@code declaringType} declares under {@code name}; none when it is not declared at all. */
    private static List<Element> members(Elements elements, String declaringType, String name) {
        TypeElement type = elements.getTypeElement(declaringType);
        if (type == null) {
            return List.of();
        }
        List<Element> found = new ArrayList<>();
        for (Element member : type.getEnclosedElements()) {
            if (member.getSimpleName().contentEquals(name)) {
                found.add(member);
            }
        }
        return found;
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
