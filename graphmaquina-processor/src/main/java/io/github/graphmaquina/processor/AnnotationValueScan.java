package io.github.graphmaquina.processor;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

/**
 * Scans the values of the annotations a type's declarations carry, on the type itself and on each of its members,
 * nested annotations and arrays included, for what keeps the processor from building that type yet or at all: a
 * value javac has not resolved, or a class literal that names an export that does not hold.
 */
final class AnnotationValueScan {

    private AnnotationValueScan() { }

    /**
     * Whether a declaration of {@code type}, on the facade or on one of its members, holds a value of an annotation
     * this processor claims, one of {@code claimed}, that javac has not resolved, nested annotations and arrays
     * included: a class literal, or a constant that gives a text or a number. javac hands such a value to the
     * processor as the text of an error: a string where its element declares something else, or, where its element
     * declares a string, one whose source form, which {@link AnnotationValue#toString()} returns, is not a string
     * literal, so a text that reads like that error is still a text. javac enters every source again in the next
     * round, where the value names a type that round brought, and reports it once processing is over if none did.
     * The values of any other annotation are never read: no model is built from them.
     */
    static boolean holdsUnresolvedValue(TypeElement type, Set<String> claimed) {
        return anyDeclaration(type, (Element element) -> unresolved(element, claimed));
    }

    /**
     * Whether a declaration of {@code type}, on the facade or on one of its members, names an export of
     * {@code invalidExports}, the exports that do not hold: its class literal is read wherever the vocabulary puts
     * it, nested annotations and arrays included.
     */
    static boolean usesInvalidExport(TypeElement type, Set<String> invalidExports) {
        if (invalidExports.isEmpty()) {
            return false;
        }
        return anyDeclaration(type, (Element element) -> names(element, invalidExports));
    }

    /**
     * Whether {@code type} itself or one of its members, in the order it declares them, is a declaration
     * {@code holds} accepts.
     */
    private static boolean anyDeclaration(TypeElement type, Predicate<Element> holds) {
        if (holds.test(type)) {
            return true;
        }
        for (Element member : type.getEnclosedElements()) {
            if (holds.test(member)) {
                return true;
            }
        }
        return false;
    }

    private static boolean unresolved(Element element, Set<String> claimed) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            if (mirror.getAnnotationType().asElement() instanceof TypeElement annotation
                    && claimed.contains(annotation.getQualifiedName().toString())
                    && unresolved(mirror)) {
                return true;
            }
        }
        return false;
    }

    private static boolean unresolved(AnnotationMirror mirror) {
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> value
                : mirror.getElementValues().entrySet()) {
            if (unresolved(value.getKey().getReturnType(), value.getValue())) {
                return true;
            }
        }
        return false;
    }

    private static boolean unresolved(TypeMirror declared, AnnotationValue value) {
        return switch (value.getValue()) {
            case String text -> !(declared instanceof DeclaredType type
                    && ((TypeElement) type.asElement()).getQualifiedName().contentEquals("java.lang.String"))
                    || !value.toString().startsWith("\"");
            case AnnotationMirror nested -> unresolved(nested);
            case List<?> values -> declared instanceof ArrayType array && values.stream()
                    .anyMatch((Object item) -> unresolved(array.getComponentType(), (AnnotationValue) item));
            default -> false;
        };
    }

    private static boolean names(Element element, Set<String> invalidExports) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            if (names(mirror, invalidExports)) {
                return true;
            }
        }
        return false;
    }

    private static boolean names(AnnotationMirror mirror, Set<String> invalidExports) {
        for (AnnotationValue value : mirror.getElementValues().values()) {
            if (names(value, invalidExports)) {
                return true;
            }
        }
        return false;
    }

    private static boolean names(AnnotationValue value, Set<String> invalidExports) {
        return switch (value.getValue()) {
            case DeclaredType type -> type.asElement() instanceof TypeElement element
                    && invalidExports.contains(element.getQualifiedName().toString());
            case AnnotationMirror nested -> names(nested, invalidExports);
            case List<?> values -> values.stream()
                    .anyMatch((Object item) -> names((AnnotationValue) item, invalidExports));
            default -> false;
        };
    }
}
