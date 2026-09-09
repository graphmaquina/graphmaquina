package io.github.graphmaquina.processor;

import io.github.graphmaquina.annotation.internal.SymbolKind;
import io.github.graphmaquina.processor.model.Binding;
import io.github.graphmaquina.processor.model.Property;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import lombok.RequiredArgsConstructor;

/** Resolves property accessors by the conventions of spec §4.2: p()/getP()/isP() and setP(x)/p(x). */
@RequiredArgsConstructor
final class Accessors {

    private final Signatures signatures;

    /**
     * Every public instance method {@code owner} has under {@code name} with {@code arity} parameters,
     * inherited ones included. Which of them suits a use is decided on its signature as a member of the owner,
     * never on the order they are found in.
     */
    List<ExecutableElement> methods(DeclaredType owner, String name, int arity) {
        List<ExecutableElement> found = new ArrayList<>();
        for (Element member : signatures.elements().getAllMembers((TypeElement) owner.asElement())) {
            if (member.getKind() == ElementKind.METHOD && member.getSimpleName().contentEquals(name)) {
                ExecutableElement method = (ExecutableElement) member;
                if (isPublicInstance(method) && method.getParameters().size() == arity) {
                    found.add(method);
                }
            }
        }
        return found;
    }

    /** The property whose accessor is {@code method}, read as a member of {@code owner}. */
    Property property(DeclaredType owner, ExecutableElement method) {
        return new Property(method.getSimpleName().toString(),
                new Binding((TypeElement) owner.asElement(), method, signatures.asMemberOf(owner, method),
                        SymbolKind.PROPERTY, List.of(), false, false));
    }

    /** The names a reader of {@code property} may have, in the order they are tried. */
    static List<String> readerNames(String property) {
        String capitalized = capitalize(property);
        return List.of(property, "get" + capitalized, "is" + capitalized);
    }

    /** The names a writer of {@code property} may have, in the order they are tried. */
    static List<String> writerNames(String property) {
        return List.of("set" + capitalize(property), property);
    }

    static boolean isPublicInstance(ExecutableElement method) {
        return method.getModifiers().contains(Modifier.PUBLIC) && !method.getModifiers().contains(Modifier.STATIC);
    }

    static String capitalize(String property) {
        return Character.toUpperCase(property.charAt(0)) + property.substring(1);
    }
}
