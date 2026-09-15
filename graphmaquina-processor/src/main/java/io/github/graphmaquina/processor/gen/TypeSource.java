package io.github.graphmaquina.processor.gen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;

/** Renders a parameter type as Java source, including fully qualified references in its type annotations. */
final class TypeSource {

    private TypeSource() { }

    static String render(TypeMirror type) {
        return switch (type.getKind()) {
            case ARRAY -> array((ArrayType) type);
            case DECLARED, ERROR -> declared((DeclaredType) type);
            case TYPEVAR -> annotations(type) + ((TypeVariable) type).asElement().getSimpleName();
            case WILDCARD -> wildcard((WildcardType) type);
            case BOOLEAN, BYTE, SHORT, INT, LONG, CHAR, FLOAT, DOUBLE, VOID ->
                    annotations(type) + type.getKind().name().toLowerCase(Locale.ROOT);
            default -> throw new IllegalArgumentException("not a source parameter type: " + type);
        };
    }

    private static String declared(DeclaredType type) {
        TypeElement element = (TypeElement) type.asElement();
        String name;
        if (type.getEnclosingType().getKind() != TypeKind.NONE) {
            name = render(type.getEnclosingType()) + "." + annotations(type) + element.getSimpleName();
        } else {
            String qualified = element.getQualifiedName().toString();
            int separator = qualified.lastIndexOf('.') + 1;
            name = qualified.substring(0, separator) + annotations(type) + qualified.substring(separator);
        }
        if (type.getTypeArguments().isEmpty()) {
            return name;
        }
        return name + "<" + String.join(", ", type.getTypeArguments().stream().map(TypeSource::render).toList()) + ">";
    }

    /** Array dimensions appear outermost first in Java source, before the component's dimensions. */
    private static String array(ArrayType type) {
        StringBuilder dimensions = new StringBuilder();
        TypeMirror component = type;
        while (component instanceof ArrayType array) {
            String annotation = annotations(array);
            if (!annotation.isEmpty()) {
                dimensions.append(' ').append(annotation);
            }
            dimensions.append("[]");
            component = array.getComponentType();
        }
        return render(component) + dimensions;
    }

    private static String wildcard(WildcardType type) {
        String source = annotations(type) + "?";
        if (type.getExtendsBound() != null) {
            return source + " extends " + render(type.getExtendsBound());
        }
        if (type.getSuperBound() != null) {
            return source + " super " + render(type.getSuperBound());
        }
        return source;
    }

    private static String annotations(TypeMirror type) {
        StringBuilder source = new StringBuilder();
        for (AnnotationMirror annotation : type.getAnnotationMirrors()) {
            source.append(annotation(annotation)).append(' ');
        }
        return source.toString();
    }

    private static String annotation(AnnotationMirror annotation) {
        String name = "@" + ((TypeElement) annotation.getAnnotationType().asElement()).getQualifiedName();
        if (annotation.getElementValues().isEmpty()) {
            return name;
        }
        List<String> values = new ArrayList<>();
        annotation.getElementValues().forEach((method, value) ->
                values.add(method.getSimpleName() + "=" + value(value)));
        return name + "(" + String.join(", ", values) + ")";
    }

    private static String value(AnnotationValue value) {
        return switch (value.getValue()) {
            case VariableElement constant -> ((TypeElement) constant.getEnclosingElement()).getQualifiedName()
                    + "." + constant.getSimpleName();
            case TypeMirror type -> render(type) + ".class";
            case AnnotationMirror annotation -> annotation(annotation);
            case List<?> values -> "{" + String.join(", ", values.stream()
                    .map(item -> value((AnnotationValue) item)).toList()) + "}";
            // javac renders scalar literals with Java quoting and numeric suffixes; they contain no type references.
            default -> value.toString();
        };
    }
}
