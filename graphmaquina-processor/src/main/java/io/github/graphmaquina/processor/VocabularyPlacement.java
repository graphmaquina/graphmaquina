package io.github.graphmaquina.processor;

import io.github.graphmaquina.annotation.GenerateGraphApi;
import io.github.graphmaquina.annotation.internal.GeneratedVocabulary;
import io.github.graphmaquina.codegen.manifest.CompileManifest;
import io.github.graphmaquina.codegen.manifest.ExportIndex;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

/**
 * Reports the generated vocabulary written where no reader of this processor reads it.
 *
 * <p>javac accepts a generated annotation on any element its targets allow, and a {@code Transition} may target
 * any method and any type, since no target says "an abstract operation of this facade". The processor claims the
 * vocabulary, so nothing else ever reports that it was not read; yet the model builders read it on a
 * {@code @GenerateGraphApi} interface and on that interface's abstract operations only. A declaration anywhere
 * else would be dropped without a word, and the graph built would not be the one the author declared, so each
 * one is reported where it is written: as {@code SG504} on a default, static or private method of the facade the
 * vocabulary belongs to, or of any facade when it names no machine, since only an abstract operation declares an
 * edge, and as {@code SG606} on any other element no builder reads it on, with the machine it belongs to named.
 * On the facade of another machine and on that facade's abstract operations a builder does read it: there that
 * facade's builder refuses it as {@code SG606}, and this class reports nothing.</p>
 *
 * <p>Either way the machine the vocabulary belongs to misses a declaration of its author, and so does a machine
 * whose vocabulary sits on another facade where a builder reads it, which that facade's builder refuses as
 * {@code SG606}. {@link #report(Set, RoundEnvironment)} returns such a machine, and the processor withholds it:
 * it is still checked declaration by declaration, but no conclusion is drawn from its graph as a whole, and it is
 * never generated.</p>
 */
final class VocabularyPlacement {

    private static final String OPERATION = "SG504";
    private static final String FOREIGN = "SG606";

    private final Set<String> vocabulary;
    private final Annotations annotations;
    private final Diagnostics diagnostics;

    VocabularyPlacement(CompileManifest manifest, Annotations annotations, Diagnostics diagnostics) {
        this.vocabulary = vocabulary(manifest);
        this.annotations = annotations;
        this.diagnostics = diagnostics;
    }

    /**
     * Reports every vocabulary annotation of {@code annotationTypes} that {@code round} carries where it is never
     * read, and answers with the qualified names of the machines whose graph misses vocabulary of theirs: the ones
     * that vocabulary was generated for, and the ones whose vocabulary sits on the facade of another machine,
     * where that facade's builder refuses it.
     */
    Set<String> report(Set<? extends TypeElement> annotationTypes, RoundEnvironment round) {
        Set<String> machines = new LinkedHashSet<>();
        for (TypeElement annotation : annotationTypes) {
            if (!vocabulary.contains(annotation.getQualifiedName().toString())) {
                continue;
            }
            Optional<String> machine = machine(annotation);
            for (Element element : round.getElementsAnnotatedWith(annotation)) {
                if (read(element)) {
                    // The builder of that facade refuses the vocabulary of another machine as SG606 where it reads it.
                    if (machine.isPresent() && !machine.get().contentEquals(facade(element).getQualifiedName())) {
                        machines.add(machine.get());
                    }
                    continue;
                }
                for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
                    if (mirror.getAnnotationType().asElement().equals(annotation)) {
                        report(element, mirror, annotation, machine);
                        machine.ifPresent(machines::add);
                    }
                }
            }
        }
        return machines;
    }

    /** The machine {@code annotation} was generated for, as its {@code GeneratedVocabulary} metadata names it. */
    private Optional<String> machine(TypeElement annotation) {
        return annotations.find(annotation, GeneratedVocabulary.class)
                .map((AnnotationMirror metadata) -> TypedValues.classLiteral(annotations.value(metadata, "machine")))
                .filter((TypeMirror machine) -> machine instanceof DeclaredType)
                .map((TypeMirror machine) -> ((TypeElement) ((DeclaredType) machine).asElement()).getQualifiedName().toString());
    }

    /**
     * Whether a builder reads the vocabulary of {@code element}: on a facade, or on an abstract operation of a
     * facade. Which facade it is does not matter here: the builder of a facade refuses the vocabulary of another
     * machine where it reads it.
     */
    private boolean read(Element element) {
        if (element instanceof TypeElement type) {
            return isFacade(type);
        }
        return element instanceof ExecutableElement method && method.getEnclosingElement() instanceof TypeElement owner
                && isFacade(owner) && StructuralChecks.isOperation(method);
    }

    /** The facade a read placement belongs to: {@code element} itself, or the facade that declares the operation. */
    private static TypeElement facade(Element element) {
        return element instanceof TypeElement type ? type : (TypeElement) element.getEnclosingElement();
    }

    /**
     * SG504 for the vocabulary on a default, static or private method of the facade it belongs to, or of any facade
     * when it names no machine; SG606 on any other element no builder reads it on, naming the facade it belongs to,
     * so that the place the diagnostic points to is one that reads it.
     */
    private void report(Element element, AnnotationMirror mirror, TypeElement annotation, Optional<String> machine) {
        String name = annotation.getQualifiedName().toString();
        if (element instanceof ExecutableElement method && method.getEnclosingElement() instanceof TypeElement owner
                && isFacade(owner) && machine.map((String facade) -> owner.getQualifiedName().contentEquals(facade)).orElse(true)) {
            DiagnosticAnchor.of(element, mirror).report(diagnostics, OPERATION, method.getSimpleName() + " is a "
                    + kind(method) + " method, so its " + name + " is never read: an edge is declared on an abstract"
                    + " operation of " + owner.getSimpleName() + ", or on " + owner.getSimpleName() + " itself");
            return;
        }
        DiagnosticAnchor.of(element, mirror).report(diagnostics, FOREIGN, name + " is read on a @GenerateGraphApi"
                + " interface and its abstract operations only, so it is never read on " + describe(element) + ": "
                + machine.map((String facade) -> "declare it on " + facade + ", whose vocabulary it is")
                        .orElse("declare it on the facade whose vocabulary it is"));
    }

    private boolean isFacade(TypeElement type) {
        return annotations.find(type, GenerateGraphApi.class).isPresent();
    }

    private static String kind(ExecutableElement method) {
        if (method.isDefault()) {
            return "default";
        }
        return method.getModifiers().contains(Modifier.STATIC) ? "static" : "private";
    }

    private static String describe(Element element) {
        if (element instanceof TypeElement type) {
            return type.getQualifiedName().toString();
        }
        if (element.getEnclosingElement() instanceof TypeElement owner) {
            return owner.getQualifiedName() + "." + element.getSimpleName();
        }
        return element.getSimpleName().toString();
    }

    /** Every generated annotation type of the compilation: the ones it produces and the ones it depends on. */
    private static Set<String> vocabulary(CompileManifest manifest) {
        Set<String> types = new LinkedHashSet<>(manifest.local().annotationTypes());
        for (ExportIndex dependency : manifest.dependencies()) {
            types.addAll(dependency.annotationTypes());
        }
        return Set.copyOf(types);
    }
}
