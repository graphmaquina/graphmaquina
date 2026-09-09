package io.stategraph.processor;

import javax.lang.model.element.AnnotationValue;
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
}
