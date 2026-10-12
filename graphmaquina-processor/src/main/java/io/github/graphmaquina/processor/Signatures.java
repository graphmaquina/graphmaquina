package io.github.graphmaquina.processor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import lombok.RequiredArgsConstructor;

/** Builds the types the bindings must match and compares types. */
@RequiredArgsConstructor
final class Signatures {

    private final Elements elements;
    private final Types types;

    /**
     * The type {@code qualifiedName} names, with {@code args} as its type arguments. The type is looked up by its
     * canonical name as {@link CanonicalNames} looks one up for code of a named package.
     */
    DeclaredType declared(String qualifiedName, TypeMirror... args) {
        TypeElement element = CanonicalNames.type(elements, qualifiedName);
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

    /**
     * Whether {@code type} is a subtype of {@code supertype} as it is, without the unchecked conversion that lets a
     * raw type be assigned to a parameterized one.
     */
    boolean subtypeOf(TypeMirror type, TypeMirror supertype) {
        return types.isSubtype(type, supertype);
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

    /** Whether {@code from} could be passed where {@code to} is expected once both lose their arguments. */
    boolean erasureAssignable(TypeMirror from, TypeMirror to) {
        return types.isAssignable(types.erasure(from), types.erasure(to));
    }

    /** The signature {@code method} has as a member of {@code owner}, with the host's arguments substituted. */
    ExecutableType asMemberOf(DeclaredType owner, ExecutableElement method) {
        return (ExecutableType) types.asMemberOf(owner, method);
    }

    /**
     * The public method of one of {@code owners}, given by qualified name, that {@code method} overrides as a member
     * of {@code type}, if there is one. It answers for a method {@code type} declares itself, which overrides
     * through {@code type}'s own supertypes; {@link javax.lang.model.util.Elements#overrides} answers false for an
     * abstract method of an interface that does not extend the owner, which is what {@link #implemented} is for.
     */
    Optional<ExecutableElement> overridden(ExecutableElement method, TypeElement type, List<String> owners) {
        for (ExecutableElement candidate : publicMethods(owners)) {
            if (candidate.getSimpleName().contentEquals(method.getSimpleName())
                    && elements.overrides(method, candidate, type)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * Whether one of the public methods of {@code owners}, given by qualified name, implements {@code method} in a
     * class that implements {@code type} and has that public method as written in {@code owners}, as a generated
     * companion writes the methods of {@code Machine} or {@code Machines} and inherits those of {@code Object}. One
     * does when, as members of {@code type}, it has the name of {@code method}, a signature that is a subsignature of
     * {@code method}'s, and a result that can stand for {@code method}'s: {@code void} for {@code void}, the same
     * primitive type, or a subtype once its own type variables are replaced by those of {@code method}. Those are
     * the cases of return-type-substitutability (JLS 8.4.5) whose override draws no unchecked warning (JLS
     * 8.4.8.3). Throws clauses are not compared: a class may declare fewer exceptions than the method it implements.
     */
    boolean implemented(ExecutableElement method, TypeElement type, List<String> owners) {
        for (ExecutableElement candidate : publicMethods(owners)) {
            if (implementedBy(method, candidate, type)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The public method of one of {@code owners}, given by qualified name, that {@code method} redeclares as a member
     * of {@code type}, whatever their results: one with its name whose signature is a subsignature of
     * {@code method}'s. Where {@link #implemented} answers false, such a method's result cannot stand for
     * {@code method}'s.
     */
    Optional<ExecutableElement> redeclared(ExecutableElement method, TypeElement type, List<String> owners) {
        DeclaredType site = (DeclaredType) type.asType();
        for (ExecutableElement candidate : publicMethods(owners)) {
            if (candidate.getSimpleName().contentEquals(method.getSimpleName())
                    && types.isSubsignature(asMemberOf(site, candidate), asMemberOf(site, method))) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** Whether {@code builtIn}, written as a member of {@code type}, implements {@code method} there; see {@link #implemented}. */
    boolean implementedBy(ExecutableElement method, ExecutableElement builtIn, TypeElement type) {
        if (!method.getSimpleName().contentEquals(builtIn.getSimpleName())) {
            return false;
        }
        DeclaredType site = (DeclaredType) type.asType();
        ExecutableType written = asMemberOf(site, builtIn);
        ExecutableType required = asMemberOf(site, method);
        return types.isSubsignature(written, required) && substitutable(written, required);
    }

    /**
     * Every abstract method {@code type} inherits without declaring it, other than the methods of {@code owners},
     * given by qualified name, and the ones a public method of {@code owners} already implements as a member of
     * {@code type}, redeclarations in an unrelated interface included: the methods a class implementing
     * {@code type} with the methods of {@code owners} would still have to implement.
     */
    List<ExecutableElement> inheritedAbstract(TypeElement type, List<String> owners) {
        List<ExecutableElement> inherited = new ArrayList<>();
        for (ExecutableElement method : ElementFilter.methodsIn(elements.getAllMembers(type))) {
            TypeElement declaring = (TypeElement) method.getEnclosingElement();
            if (declaring.equals(type) || !method.getModifiers().contains(Modifier.ABSTRACT)
                    || owners.contains(declaring.getQualifiedName().toString())
                    || implemented(method, type, owners)) {
                continue;
            }
            inherited.add(method);
        }
        return inherited;
    }

    /**
     * The instance methods named {@code name}, abstract or default, that {@code type} declares or inherits and whose
     * parameter types, as members of {@code type} and once erased, are the erasures of {@code parameters}: the
     * methods beside which a class implementing {@code type} cannot declare a static method of that name whose
     * parameters are those erasures, as a generated companion writes each class it takes. That method would hide one
     * the class inherits, or have the signature of one it declares (JLS 8.4.8.2, 8.4.2). A static or private method
     * of an interface is no member a class inherits, and a method whose parameter types differ is an overload.
     */
    List<ExecutableElement> clashing(TypeElement type, String name, List<TypeMirror> parameters) {
        DeclaredType site = (DeclaredType) type.asType();
        List<ExecutableElement> clashing = new ArrayList<>();
        for (ExecutableElement method : ElementFilter.methodsIn(elements.getAllMembers(type))) {
            if (!method.getSimpleName().contentEquals(name) || method.getModifiers().contains(Modifier.STATIC)
                    || method.getModifiers().contains(Modifier.PRIVATE) || method.getParameters().size() != parameters.size()) {
                continue;
            }
            List<? extends TypeMirror> declared = asMemberOf(site, method).getParameterTypes();
            boolean same = true;
            for (int at = 0; at < parameters.size(); at++) {
                same &= types.isSameType(types.erasure(declared.get(at)), types.erasure(parameters.get(at)));
            }
            if (same) {
                clashing.add(method);
            }
        }
        return clashing;
    }

    /** The public methods {@code owners}, given by qualified name, declare themselves, in that order. */
    private List<ExecutableElement> publicMethods(List<String> owners) {
        List<ExecutableElement> methods = new ArrayList<>();
        for (String owner : owners) {
            for (ExecutableElement candidate : ElementFilter.methodsIn(elements.getTypeElement(owner).getEnclosedElements())) {
                if (candidate.getModifiers().contains(Modifier.PUBLIC)) {
                    methods.add(candidate);
                }
            }
        }
        return methods;
    }

    /** Whether the result of {@code written} can stand for that of {@code required} without an unchecked warning. */
    private boolean substitutable(ExecutableType written, ExecutableType required) {
        TypeMirror result = written.getReturnType();
        TypeMirror expected = required.getReturnType();
        if (result.getKind() == TypeKind.VOID || result.getKind().isPrimitive()) {
            return result.getKind() == expected.getKind();
        }
        return types.isSubtype(substitute(result, written.getTypeVariables(), required.getTypeVariables()), expected);
    }

    /** {@code type} with each of {@code from} replaced by the variable at the same position of {@code to}. */
    private TypeMirror substitute(TypeMirror type, List<? extends TypeVariable> from, List<? extends TypeVariable> to) {
        if (from.isEmpty() || from.size() != to.size()) {
            return type;
        }
        if (type instanceof TypeVariable variable) {
            for (int at = 0; at < from.size(); at++) {
                if (types.isSameType(variable, from.get(at))) {
                    return to.get(at);
                }
            }
            return variable;
        }
        if (type instanceof ArrayType array) {
            return types.getArrayType(substitute(array.getComponentType(), from, to));
        }
        if (type instanceof WildcardType wildcard) {
            return types.getWildcardType(
                    wildcard.getExtendsBound() == null ? null : substitute(wildcard.getExtendsBound(), from, to),
                    wildcard.getSuperBound() == null ? null : substitute(wildcard.getSuperBound(), from, to));
        }
        if (type instanceof DeclaredType declared && !declared.getTypeArguments().isEmpty()) {
            List<? extends TypeMirror> arguments = declared.getTypeArguments();
            TypeMirror[] substituted = new TypeMirror[arguments.size()];
            for (int at = 0; at < arguments.size(); at++) {
                substituted[at] = substitute(arguments.get(at), from, to);
            }
            TypeElement element = (TypeElement) declared.asElement();
            return declared.getEnclosingType() instanceof DeclaredType outer && !outer.getTypeArguments().isEmpty()
                    ? types.getDeclaredType((DeclaredType) substitute(outer, from, to), element, substituted)
                    : types.getDeclaredType(element, substituted);
        }
        return type;
    }

    Types types() {
        return types;
    }

    Elements elements() {
        return elements;
    }
}
