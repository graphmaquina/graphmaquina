package io.github.graphmaquina.processor.gen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

/**
 * Renders a type as Java source, including fully qualified references in its type annotations: an operation's input
 * type, a parameter type that pins an overload, a {@code failsOn} class, or the kind or a throwable class of a
 * recovery rule.
 *
 * <p>Every declared type is written by its qualified name, so {@link #written} answers which identifiers a
 * rendering starts its qualified names with, a package or, for a type of the unnamed package, the top-level type it
 * is or is nested in: a type of one of those names would hide it wherever it heads a name, and a variable of one
 * of those names would obscure it where an annotation value names an enum constant, the one place of a rendering
 * an expression reads, which {@link #written} answers apart.</p>
 */
final class TypeSource {

    private final Map<String, String> roots = new LinkedHashMap<>();
    private final Map<String, String> typeRoots = new LinkedHashMap<>();
    private final Map<String, String> expressions = new LinkedHashMap<>();

    private TypeSource() { }

    static String render(TypeMirror type) {
        return new TypeSource().source(type);
    }

    /**
     * What {@link #render} writes for {@code type} at the head of its names: the first segment of every qualified
     * name, each with the first such name, and the ones an annotation value reads.
     */
    static Written written(TypeMirror type) {
        TypeSource source = new TypeSource();
        source.source(type);
        return new Written(source.roots, source.typeRoots, Map.of(), source.expressions);
    }

    private String source(TypeMirror type) {
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

    private String declared(DeclaredType type) {
        TypeElement element = (TypeElement) type.asElement();
        String name;
        if (type.getEnclosingType().getKind() != TypeKind.NONE) {
            name = source(type.getEnclosingType()) + "." + annotations(type) + element.getSimpleName();
        } else {
            String qualified = qualified(element);
            int separator = qualified.lastIndexOf('.') + 1;
            name = qualified.substring(0, separator) + annotations(type) + qualified.substring(separator);
        }
        if (type.getTypeArguments().isEmpty()) {
            return name;
        }
        List<String> arguments = new ArrayList<>();
        for (TypeMirror argument : type.getTypeArguments()) {
            arguments.add(source(argument));
        }
        return name + "<" + String.join(", ", arguments) + ">";
    }

    /** Array dimensions appear outermost first in Java source, before the component's dimensions. */
    private String array(ArrayType type) {
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
        return source(component) + dimensions;
    }

    private String wildcard(WildcardType type) {
        String source = annotations(type) + "?";
        if (type.getExtendsBound() != null) {
            return source + " extends " + source(type.getExtendsBound());
        }
        if (type.getSuperBound() != null) {
            return source + " super " + source(type.getSuperBound());
        }
        return source;
    }

    private String annotations(TypeMirror type) {
        StringBuilder source = new StringBuilder();
        for (AnnotationMirror annotation : type.getAnnotationMirrors()) {
            source.append(annotation(annotation)).append(' ');
        }
        return source.toString();
    }

    private String annotation(AnnotationMirror annotation) {
        String name = "@" + qualified((TypeElement) annotation.getAnnotationType().asElement());
        if (annotation.getElementValues().isEmpty()) {
            return name;
        }
        List<String> values = new ArrayList<>();
        annotation.getElementValues().forEach((method, value) ->
                values.add(method.getSimpleName() + "=" + value(value)));
        return name + "(" + String.join(", ", values) + ")";
    }

    private String value(AnnotationValue value) {
        return switch (value.getValue()) {
            case VariableElement constant -> read((TypeElement) constant.getEnclosingElement()) + "."
                    + constant.getSimpleName();
            case TypeMirror type -> source(type) + ".class";
            case AnnotationMirror annotation -> annotation(annotation);
            case List<?> values -> "{" + String.join(", ", values.stream()
                    .map(item -> value((AnnotationValue) item)).toList()) + "}";
            // javac renders scalar literals with Java quoting and numeric suffixes; they contain no type references.
            default -> value.toString();
        };
    }

    /**
     * The qualified name of {@code type} where an annotation value reads a constant of it, an expression whose
     * first identifier a field of that name would take.
     */
    private String read(TypeElement type) {
        String qualified = qualified(type);
        int dot = qualified.indexOf('.');
        expressions.putIfAbsent(dot < 0 ? qualified : qualified.substring(0, dot), qualified);
        return qualified;
    }

    /** The qualified name of {@code type}, whose first segment the rendering then starts with. */
    private String qualified(TypeElement type) {
        String qualified = type.getQualifiedName().toString();
        int dot = qualified.indexOf('.');
        Map<String, String> heads = Names.packageOf(type).isUnnamed() ? typeRoots : roots;
        heads.putIfAbsent(dot < 0 ? qualified : qualified.substring(0, dot), qualified);
        return qualified;
    }
}
