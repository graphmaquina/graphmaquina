package io.github.graphmaquina.processor;

import com.sun.source.util.Trees;
import java.util.Optional;
import java.util.OptionalLong;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;

/**
 * Reports and counts javac errors as "[SGnnn] message". Explicit annotation values use verified source expressions,
 * including nested repeatables; defaults and declarations without source retain Messager's available anchor.
 * The source expressions come from javac's {@code Trees}, which only a plain javac processing environment provides.
 * The fallback covers a processing environment that {@code Trees} refuses (IllegalArgumentException) and a runtime
 * on which the {@code Trees} class itself cannot be loaded (NoClassDefFoundError, as without the jdk.compiler
 * module). In either case, the first being a wrapped environment, no source is available: every diagnostic is
 * reported through {@code Messager} with the element, annotation and value anchors, as for a declaration without
 * source.
 */
final class Diagnostics {

    private final Messager messager;
    private final Trees trees;
    private final AnnotationSources sources;
    private int errors;

    Diagnostics(ProcessingEnvironment environment) {
        messager = environment.getMessager();
        trees = treesOf(environment);
        sources = new AnnotationSources(trees);
    }

    /**
     * The trees of a plain javac environment; null when the environment is not one, or when the processor's class
     * loader cannot load {@code Trees} even in a javac environment, so that there is no source to offer.
     */
    private static Trees treesOf(ProcessingEnvironment environment) {
        try {
            return Trees.instance(environment);
        } catch (IllegalArgumentException | NoClassDefFoundError unsupported) {
            return null;
        }
    }

    /** Source start of an explicitly written value, shared by diagnostic emission and occurrence ordering. */
    OptionalLong sourceStart(DiagnosticAnchor anchor) {
        if (anchor.annotation().isEmpty() || anchor.value().isEmpty()) {
            return OptionalLong.empty();
        }
        Optional<AnnotationSources.Location> location = sources.find(anchor.element(), anchor.annotation().get(),
                anchor.value().get());
        return location.isPresent() ? OptionalLong.of(location.get().start()) : OptionalLong.empty();
    }

    /** Reported without an anchor, for a failure of the compilation itself rather than of a declaration. */
    void error(String code, String message) {
        errors++;
        messager.printMessage(Diagnostic.Kind.ERROR, "[" + code + "] " + message);
    }

    void error(String code, String message, Element element) {
        errors++;
        messager.printMessage(Diagnostic.Kind.ERROR, "[" + code + "] " + message, element);
    }

    void error(String code, String message, Element element, AnnotationMirror mirror) {
        errors++;
        messager.printMessage(Diagnostic.Kind.ERROR, "[" + code + "] " + message, element, mirror);
    }

    /** Reports once at the exact expression when bound, otherwise at Messager's best available anchor. */
    void error(String code, String message, Element element, AnnotationMirror mirror, AnnotationValue value) {
        errors++;
        String formatted = "[" + code + "] " + message;
        Optional<AnnotationSources.Location> location = sources.find(element, mirror, value);
        if (location.isPresent()) {
            AnnotationSources.Location source = location.get();
            trees.printMessage(Diagnostic.Kind.ERROR, formatted, source.tree(), source.unit());
        } else {
            messager.printMessage(Diagnostic.Kind.ERROR, formatted, element, mirror, value);
        }
    }

    int errors() {
        return errors;
    }
}
