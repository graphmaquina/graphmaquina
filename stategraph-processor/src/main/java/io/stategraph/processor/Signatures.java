package io.stategraph.processor;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import lombok.RequiredArgsConstructor;

/** Builds the types the bindings must match and compares types. */
@RequiredArgsConstructor
final class Signatures {

    private final Elements elements;
    private final Types types;

    DeclaredType declared(String qualifiedName, TypeMirror... args) {
        TypeElement element = elements.getTypeElement(qualifiedName);
        if (element == null) {
            throw new IllegalStateException(qualifiedName + " is not on the classpath");
        }
        return types.getDeclaredType(element, args);
    }

    boolean same(TypeMirror a, TypeMirror b) {
        return types.isSameType(a, b);
    }

    boolean assignable(TypeMirror from, TypeMirror to) {
        return types.isAssignable(from, to);
    }

    boolean subtype(TypeMirror type, String qualifiedName) {
        return types.isSubtype(types.erasure(type), types.erasure(declared(qualifiedName)));
    }

    WildcardType extendsWildcard(TypeMirror bound) {
        return types.getWildcardType(bound, null);
    }

    boolean isEnum(TypeMirror type) {
        return type.getKind() == TypeKind.DECLARED && ((DeclaredType) type).asElement().getKind() == ElementKind.ENUM;
    }

    boolean erasureIs(TypeMirror type, String qualifiedName) {
        return types.isSameType(types.erasure(type), types.erasure(declared(qualifiedName)));
    }

    Types types() {
        return types;
    }

    Elements elements() {
        return elements;
    }
}
