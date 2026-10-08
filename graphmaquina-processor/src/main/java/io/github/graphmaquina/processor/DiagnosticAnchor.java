package io.github.graphmaquina.processor;

import java.util.Objects;
import java.util.Optional;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;

/**
 * Where a diagnostic belongs: the declaration that carries it and, when they are known, the annotation and the
 * exact value inside that annotation.
 *
 * <p>Diagnostics binds explicit values to their source expressions, including values in implicit repeatable
 * containers. When source is unavailable or a value is defaulted, Messager retains the available anchor. The
 * semantic values stay reachable alongside the model until validation reports a problem.</p>
 */
record DiagnosticAnchor(Element element, Optional<AnnotationMirror> annotation, Optional<AnnotationValue> value) {

    DiagnosticAnchor {
        Objects.requireNonNull(element, "element");
        Objects.requireNonNull(annotation, "annotation");
        Objects.requireNonNull(value, "value");
    }

    /** An anchor on a declaration alone. */
    static DiagnosticAnchor of(Element element) {
        return new DiagnosticAnchor(element, Optional.empty(), Optional.empty());
    }

    /** An anchor on one annotation of a declaration. */
    static DiagnosticAnchor of(Element element, AnnotationMirror annotation) {
        return new DiagnosticAnchor(element, Optional.of(annotation), Optional.empty());
    }

    /** An anchor on one value of one annotation of a declaration. */
    static DiagnosticAnchor of(Element element, AnnotationMirror annotation, AnnotationValue value) {
        return new DiagnosticAnchor(element, Optional.of(annotation), Optional.of(value));
    }

    /** Reports {@code message} at the narrowest position this anchor knows. */
    void report(Diagnostics diagnostics, String code, String message) {
        if (annotation.isEmpty()) {
            diagnostics.error(code, message, element);
        } else if (value.isEmpty()) {
            diagnostics.error(code, message, element, annotation.get());
        } else {
            diagnostics.error(code, message, element, annotation.get(), value.get());
        }
    }
}
