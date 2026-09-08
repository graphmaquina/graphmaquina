package io.stategraph.processor;

import java.util.Optional;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import lombok.RequiredArgsConstructor;

/** Resolves property accessors by the conventions of spec §4.2: p()/getP()/isP() and setP(x)/p(x). */
@RequiredArgsConstructor
final class Accessors {

    private final Signatures signatures;

    Optional<ExecutableElement> getter(TypeElement owner, String property) {
        String capitalized = capitalize(property);
        for (String name : new String[] {property, "get" + capitalized, "is" + capitalized}) {
            for (Element member : signatures.elements().getAllMembers(owner)) {
                if (member.getKind() == ElementKind.METHOD && member.getSimpleName().contentEquals(name)) {
                    ExecutableElement method = (ExecutableElement) member;
                    if (isPublicInstance(method) && method.getParameters().isEmpty() && method.getReturnType().getKind() != TypeKind.VOID) {
                        return Optional.of(method);
                    }
                }
            }
        }
        return Optional.empty();
    }

    Optional<ExecutableElement> setter(TypeElement owner, String property, TypeMirror parameter) {
        for (String name : new String[] {"set" + capitalize(property), property}) {
            for (Element member : signatures.elements().getAllMembers(owner)) {
                if (member.getKind() == ElementKind.METHOD && member.getSimpleName().contentEquals(name)) {
                    ExecutableElement method = (ExecutableElement) member;
                    if (isPublicInstance(method) && method.getParameters().size() == 1
                            && signatures.assignable(parameter, method.getParameters().get(0).asType())) {
                        return Optional.of(method);
                    }
                }
            }
        }
        return Optional.empty();
    }

    static boolean isPublicInstance(ExecutableElement method) {
        return method.getModifiers().contains(Modifier.PUBLIC) && !method.getModifiers().contains(Modifier.STATIC);
    }

    static String capitalize(String property) {
        return Character.toUpperCase(property.charAt(0)) + property.substring(1);
    }
}
