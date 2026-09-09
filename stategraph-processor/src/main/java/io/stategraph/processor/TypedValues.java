package io.stategraph.processor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;

/**
 * Reads an annotation value as the language model already types it: an enum constant is the
 * {@link VariableElement} that declares it and a class literal is the {@link TypeMirror} it names.
 *
 * <p>Nothing here renders a value as text first. A constant read as a string loses the enum it belongs to and a
 * class literal read as a string loses the type it names, so both would have to be resolved again by name,
 * which is exactly what the typed vocabulary exists to avoid.</p>
 */
final class TypedValues {

    private TypedValues() { }

    /**
     * The enum constant {@code value} holds.
     *
     * @throws IllegalArgumentException if the element it comes from is not declared with an enum type
     */
    static VariableElement enumConstant(AnnotationValue value) {
        if (value.getValue() instanceof VariableElement constant) {
            return constant;
        }
        throw new IllegalArgumentException("the annotation value " + value + " is not an enum constant");
    }

    /**
     * The type {@code value} names.
     *
     * @throws IllegalArgumentException if the element it comes from is not declared with a class type
     */
    static TypeMirror classLiteral(AnnotationValue value) {
        if (value.getValue() instanceof TypeMirror type) {
            return type;
        }
        throw new IllegalArgumentException("the annotation value " + value + " is not a class literal");
    }

    /**
     * The name of the state {@code value} holds, and empty when it holds anything else or a constant of another
     * enum. The enum is compared by identity, not by name, so a constant only names a state of the machine that
     * declares {@code states}; javac has already refused what this returns empty for, and no code wraps that
     * refusal in one of its own.
     */
    static Optional<String> stateName(AnnotationValue value, TypeElement states) {
        if (!(value.getValue() instanceof VariableElement)) {
            return Optional.empty();
        }
        VariableElement constant = enumConstant(value);
        return constant.getEnclosingElement().equals(states)
                ? Optional.of(constant.getSimpleName().toString())
                : Optional.empty();
    }

    /** The names of every state of an array element, and empty when one of its values is not one of {@code states}. */
    static Optional<List<String>> stateNames(List<AnnotationValue> values, TypeElement states) {
        List<String> names = new ArrayList<>();
        for (AnnotationValue value : values) {
            Optional<String> name = stateName(value, states);
            if (name.isEmpty()) {
                return Optional.empty();
            }
            names.add(name.get());
        }
        return Optional.of(List.copyOf(names));
    }

    /**
     * The one value of the array element {@code name}, which is how an annotation declares something the model
     * holds at most once: none is empty, one is that value, and more is empty after reporting {@code SG607}.
     * Reading the first, or the last, of several would silently drop a declaration the author wrote.
     */
    static Optional<AnnotationValue> optional(AnnotationMirror mirror, String name, Annotations annotations,
            DiagnosticAnchor anchor, Diagnostics diagnostics) {
        List<AnnotationValue> values = annotations.array(mirror, name);
        if (values.size() > 1) {
            anchor.report(diagnostics, "SG607", name + " declares " + values.size() + " values but holds one at most");
            return Optional.empty();
        }
        return values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
    }
}
