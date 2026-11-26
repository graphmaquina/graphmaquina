package io.github.graphmaquina.processor;

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
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/**
 * Reads annotation mirrors by element name, defaults included, and unwraps @Repeatable containers. Each lookup by a
 * mirror reads that mirror's values from javac again; a reader that looks up several elements of one mirror reads
 * them once, through {@link #values}, and looks them all up in what that one reading returned.
 */
@RequiredArgsConstructor
final class Annotations {

    private final Elements elements;

    /**
     * The values of one annotation mirror by element name, defaults included, as {@link #values} read them from javac,
     * once. It answers for the one reader call that read it and is never kept.
     */
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    static final class Values {

        private final AnnotationMirror mirror;
        private final Map<? extends ExecutableElement, ? extends AnnotationValue> read;

        /**
         * The value of the element {@code name}, or empty when the mirror carries none: javac refuses an annotation
         * that omits an element without a default, and it leaves that element out of the mirror it hands over.
         */
        Optional<AnnotationValue> find(String name) {
            for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : read.entrySet()) {
                if (entry.getKey().getSimpleName().contentEquals(name)) {
                    return Optional.of(entry.getValue());
                }
            }
            return Optional.empty();
        }

        AnnotationValue value(String name) {
            return find(name).orElseThrow(() ->
                    new IllegalArgumentException(mirror.getAnnotationType() + " has no element " + name));
        }

        /** The values of an array element; a single non-array value is returned as a one-element list. */
        List<AnnotationValue> array(String name) {
            AnnotationValue value = value(name);
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
    }

    Optional<AnnotationMirror> find(Element element, Class<? extends Annotation> type) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            if (is(mirror, type)) {
                return Optional.of(mirror);
            }
        }
        return Optional.empty();
    }

    /** Direct occurrences and the ones inside the container, in source order. */
    List<AnnotationMirror> findAll(Element element, Class<? extends Annotation> single,
                                   Class<? extends Annotation> container) {
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

    /** The values of {@code mirror}, defaults included, read from javac once for every lookup a reader makes. */
    Values values(AnnotationMirror mirror) {
        return new Values(mirror, elements.getElementValuesWithDefaults(mirror));
    }

    /**
     * The value of the element {@code name}, or empty when the mirror carries none: javac refuses an annotation
     * that omits an element without a default, and it leaves that element out of the mirror it hands over.
     */
    Optional<AnnotationValue> find(AnnotationMirror mirror, String name) {
        return values(mirror).find(name);
    }

    AnnotationValue value(AnnotationMirror mirror, String name) {
        return values(mirror).value(name);
    }

    String string(AnnotationMirror mirror, String name) {
        return (String) value(mirror, name).getValue();
    }

    /** The values of an array element; a single non-array value is returned as a one-element list. */
    List<AnnotationValue> array(AnnotationMirror mirror, String name) {
        return values(mirror).array(name);
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
