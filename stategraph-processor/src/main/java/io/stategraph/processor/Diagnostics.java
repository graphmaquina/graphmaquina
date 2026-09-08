package io.stategraph.processor;

import javax.annotation.processing.Messager;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;
import lombok.RequiredArgsConstructor;

/** Reports problems as javac errors, "[SGnnn] message", anchored as precisely as the caller can. Counts them. */
@RequiredArgsConstructor
final class Diagnostics {

    private final Messager messager;
    private int errors;

    void error(String code, String message, Element element) {
        errors++;
        messager.printMessage(Diagnostic.Kind.ERROR, "[" + code + "] " + message, element);
    }

    void error(String code, String message, Element element, AnnotationMirror mirror) {
        errors++;
        messager.printMessage(Diagnostic.Kind.ERROR, "[" + code + "] " + message, element, mirror);
    }

    void error(String code, String message, Element element, AnnotationMirror mirror, AnnotationValue value) {
        errors++;
        messager.printMessage(Diagnostic.Kind.ERROR, "[" + code + "] " + message, element, mirror, value);
    }

    int errors() {
        return errors;
    }
}
