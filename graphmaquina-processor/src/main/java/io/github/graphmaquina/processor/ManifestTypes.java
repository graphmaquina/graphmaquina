package io.github.graphmaquina.processor;

import java.util.stream.Collectors;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;

/** Renders javac declaration types in the generator's canonical format, without type-use annotations. */
final class ManifestTypes {

    private ManifestTypes() { }

    static String render(TypeMirror type) {
        if (type instanceof DeclaredType declared) {
            TypeElement element = (TypeElement) declared.asElement();
            String name = declared.getEnclosingType().getKind() == TypeKind.NONE
                    ? element.getQualifiedName().toString()
                    : render(declared.getEnclosingType()) + '.' + element.getSimpleName();
            return declared.getTypeArguments().isEmpty() ? name : name + declared.getTypeArguments().stream()
                    .map(ManifestTypes::render).collect(Collectors.joining(", ", "<", ">"));
        }
        if (type instanceof ArrayType array) {
            return render(array.getComponentType()) + "[]";
        }
        if (type instanceof TypeVariable variable) {
            return variable.asElement().getSimpleName().toString();
        }
        if (type instanceof WildcardType wildcard) {
            if (wildcard.getExtendsBound() != null) {
                return "? extends " + render(wildcard.getExtendsBound());
            }
            return wildcard.getSuperBound() == null ? "?" : "? super " + render(wildcard.getSuperBound());
        }
        // The remaining declaration types are primitives or void; their kind omits any type annotations.
        return type.getKind().name().toLowerCase(java.util.Locale.ROOT);
    }
}
