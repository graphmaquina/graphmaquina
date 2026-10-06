package io.github.graphmaquina.processor;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;

/**
 * Which types generated code cannot name from the package it is generated in.
 *
 * <p>Code of a package names a type when the type and every type enclosing it are accessible there: public, or
 * declared in that very package and not private. A protected type is accessible from its own package only, since
 * no companion is a subclass of a type the author declares. A reference to a type also names its type arguments,
 * the bounds of its wildcards, the component of an array, and the annotation types and values of its type
 * annotations, so each of those is checked as well.</p>
 */
final class Accessibility {

    private Accessibility() { }

    /** The qualified names of the types among {@code type} and its enclosing types that code in {@code packageName} cannot access. */
    static Set<String> inaccessible(TypeElement type, String packageName) {
        Set<String> found = new LinkedHashSet<>();
        collect(type, packageName, found);
        return found;
    }

    /** The qualified names of every type a reference to {@code type} names that code in {@code packageName} cannot access. */
    static Set<String> inaccessible(TypeMirror type, String packageName) {
        Set<String> found = new LinkedHashSet<>();
        collect(type, packageName, found);
        return found;
    }

    /** How a diagnostic names the package {@code packageName}, the unnamed one included. */
    static String describe(String packageName) {
        return packageName.isEmpty() ? "the unnamed package" : "package " + packageName;
    }

    /** The package that declares {@code element}, however deeply it is nested, as its qualified name. */
    static String packageOf(Element element) {
        Element current = element;
        while (!(current instanceof PackageElement)) {
            current = current.getEnclosingElement();
        }
        return ((PackageElement) current).getQualifiedName().toString();
    }

    private static void collect(TypeElement type, String packageName, Set<String> found) {
        for (Element current = type; current instanceof TypeElement element; current = current.getEnclosingElement()) {
            Set<Modifier> modifiers = element.getModifiers();
            if (!modifiers.contains(Modifier.PUBLIC)
                    && (modifiers.contains(Modifier.PRIVATE) || !packageOf(element).equals(packageName))) {
                found.add(element.getQualifiedName().toString());
            }
        }
    }

    private static void collect(TypeMirror type, String packageName, Set<String> found) {
        for (AnnotationMirror annotation : type.getAnnotationMirrors()) {
            collect(annotation, packageName, found);
        }
        if (type instanceof DeclaredType declared && declared.asElement() instanceof TypeElement element) {
            collect(element, packageName, found);
            if (declared.getEnclosingType().getKind() != TypeKind.NONE) {
                collect(declared.getEnclosingType(), packageName, found);
            }
            for (TypeMirror argument : declared.getTypeArguments()) {
                collect(argument, packageName, found);
            }
        } else if (type instanceof ArrayType array) {
            collect(array.getComponentType(), packageName, found);
        } else if (type instanceof WildcardType wildcard) {
            if (wildcard.getExtendsBound() != null) {
                collect(wildcard.getExtendsBound(), packageName, found);
            }
            if (wildcard.getSuperBound() != null) {
                collect(wildcard.getSuperBound(), packageName, found);
            }
        }
    }

    private static void collect(AnnotationMirror annotation, String packageName, Set<String> found) {
        collect((TypeElement) annotation.getAnnotationType().asElement(), packageName, found);
        for (AnnotationValue value : annotation.getElementValues().values()) {
            collect(value, packageName, found);
        }
    }

    private static void collect(AnnotationValue value, String packageName, Set<String> found) {
        switch (value.getValue()) {
            case VariableElement constant -> collect((TypeElement) constant.getEnclosingElement(), packageName, found);
            case TypeMirror type -> collect(type, packageName, found);
            case AnnotationMirror annotation -> collect(annotation, packageName, found);
            case List<?> values -> {
                for (Object item : values) {
                    collect((AnnotationValue) item, packageName, found);
                }
            }
            default -> { }
        }
    }
}
