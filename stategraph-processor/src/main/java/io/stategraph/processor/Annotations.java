package io.stategraph.processor;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import lombok.RequiredArgsConstructor;

/** Reads annotation mirrors by element name, defaults included, and unwraps @Repeatable containers. */
@RequiredArgsConstructor
final class Annotations {

    private final Elements elements;

    Optional<AnnotationMirror> find(Element element, Class<? extends Annotation> type) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            if (is(mirror, type)) {
                return Optional.of(mirror);
            }
        }
        return Optional.empty();
    }

    /** Direct occurrences and the ones inside the container, in source order. */
    List<AnnotationMirror> findAll(Element element, Class<? extends Annotation> single, Class<? extends Annotation> container) {
        List<AnnotationMirror> found = new ArrayList<>();
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            if (is(mirror, single)) {
                found.add(mirror);
            } else if (is(mirror, container)) {
                found.addAll(annotations(mirror, "value"));
            }
        }
        return found;
    }

    boolean is(AnnotationMirror mirror, Class<? extends Annotation> type) {
        TypeElement declared = (TypeElement) mirror.getAnnotationType().asElement();
        return declared.getQualifiedName().contentEquals(type.getCanonicalName());
    }

    /**
     * The value of the element {@code name}, or empty when the mirror carries none: javac refuses an annotation
     * that omits an element without a default, and it leaves that element out of the mirror it hands over.
     */
    Optional<AnnotationValue> find(AnnotationMirror mirror, String name) {
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : elements.getElementValuesWithDefaults(mirror).entrySet()) {
            if (entry.getKey().getSimpleName().contentEquals(name)) {
                return Optional.of(entry.getValue());
            }
        }
        return Optional.empty();
    }

    AnnotationValue value(AnnotationMirror mirror, String name) {
        return find(mirror, name).orElseThrow(() ->
                new IllegalArgumentException(mirror.getAnnotationType() + " has no element " + name));
    }

    String string(AnnotationMirror mirror, String name) {
        return (String) value(mirror, name).getValue();
    }

    /** The values of an array element; a single non-array value is returned as a one-element list. */
    List<AnnotationValue> array(AnnotationMirror mirror, String name) {
        AnnotationValue value = value(mirror, name);
        List<AnnotationValue> values = new ArrayList<>();
        if (value.getValue() instanceof List<?> list) {
            for (Object item : list) {
                values.add((AnnotationValue) item);
            }
        } else {
            values.add(value);
        }
        return values;
    }

    List<String> strings(AnnotationMirror mirror, String name) {
        List<String> strings = new ArrayList<>();
        for (AnnotationValue value : array(mirror, name)) {
            strings.add((String) value.getValue());
        }
        return strings;
    }

    TypeMirror type(AnnotationMirror mirror, String name) {
        return (TypeMirror) value(mirror, name).getValue();
    }

    List<TypeMirror> types(AnnotationMirror mirror, String name) {
        List<TypeMirror> types = new ArrayList<>();
        for (AnnotationValue value : array(mirror, name)) {
            types.add((TypeMirror) value.getValue());
        }
        return types;
    }

    List<AnnotationMirror> annotations(AnnotationMirror mirror, String name) {
        List<AnnotationMirror> nested = new ArrayList<>();
        for (AnnotationValue value : array(mirror, name)) {
            nested.add((AnnotationMirror) value.getValue());
        }
        return nested;
    }

    /** The default {@code void.class} of a Class element. */
    boolean isVoid(TypeMirror type) {
        return type.getKind() == TypeKind.VOID;
    }
}
