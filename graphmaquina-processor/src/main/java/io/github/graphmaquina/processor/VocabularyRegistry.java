package io.github.graphmaquina.processor;

import io.github.graphmaquina.annotation.internal.GeneratedVocabulary;
import io.github.graphmaquina.annotation.internal.VocabularyKind;
import io.github.graphmaquina.codegen.manifest.CompileManifest;
import io.github.graphmaquina.codegen.manifest.ExportIndex;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;

/**
 * Finds the generated vocabulary applied to a declaration.
 *
 * <p>An annotation is recognized by the {@link GeneratedVocabulary} metadata its own annotation type carries and
 * only when the compilation's manifest lists that type, never by its simple name: an application may declare its
 * own {@code Transition} and it is not vocabulary. Repeated declarations are read through their container, so a
 * {@code Transitions} yields its {@code Transition} values in the order the declaration spells them.</p>
 */
final class VocabularyRegistry {

    private static final Map<VocabularyKind, VocabularyKind> CONTAINERS = containers();

    private final Set<String> generated;
    private final Annotations annotations;

    VocabularyRegistry(CompileManifest manifest, Elements elements) {
        this.generated = generated(manifest);
        this.annotations = new Annotations(elements);
    }

    /**
     * Every annotation of {@code kind} applied to {@code element}, direct occurrences and the ones held by the
     * repeatable container of that kind alike, in declaration order.
     */
    List<AnnotationMirror> findAll(Element element, VocabularyKind kind) {
        VocabularyKind container = CONTAINERS.get(kind);
        List<AnnotationMirror> found = new ArrayList<>();
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            Optional<VocabularyKind> declared = kindOf(mirror);
            if (declared.isEmpty()) {
                continue;
            }
            if (declared.get() == kind) {
                found.add(mirror);
            } else if (declared.get() == container) {
                for (AnnotationMirror repeated : annotations.annotations(mirror, "value")) {
                    if (kindOf(repeated).filter(nested -> nested == kind).isPresent()) {
                        found.add(repeated);
                    }
                }
            }
        }
        return List.copyOf(found);
    }

    /** The role {@code mirror} plays, or empty when its type is not vocabulary the manifest lists. */
    private Optional<VocabularyKind> kindOf(AnnotationMirror mirror) {
        TypeElement type = (TypeElement) mirror.getAnnotationType().asElement();
        if (!generated.contains(type.getQualifiedName().toString())) {
            return Optional.empty();
        }
        return annotations.find(type, GeneratedVocabulary.class)
                .map(metadata -> VocabularyKind.valueOf(
                        TypedValues.enumConstant(annotations.value(metadata, "value")).getSimpleName().toString()));
    }

    /** Every generated annotation type of the compilation: the ones it produces and the ones it depends on. */
    private static Set<String> generated(CompileManifest manifest) {
        Set<String> types = new LinkedHashSet<>(manifest.local().annotationTypes());
        for (ExportIndex dependency : manifest.dependencies()) {
            types.addAll(dependency.annotationTypes());
        }
        return Set.copyOf(types);
    }

    /** The container each repeatable kind is held by; a kind that is not repeatable has none. */
    private static Map<VocabularyKind, VocabularyKind> containers() {
        Map<VocabularyKind, VocabularyKind> containers = new EnumMap<>(VocabularyKind.class);
        containers.put(VocabularyKind.STATE_POLICY, VocabularyKind.STATE_POLICIES);
        containers.put(VocabularyKind.TRANSITION, VocabularyKind.TRANSITIONS);
        containers.put(VocabularyKind.REJECT, VocabularyKind.REJECTS);
        containers.put(VocabularyKind.WATCH, VocabularyKind.WATCHES);
        containers.put(VocabularyKind.SUBMACHINE, VocabularyKind.SUBMACHINES);
        return Map.copyOf(containers);
    }
}
