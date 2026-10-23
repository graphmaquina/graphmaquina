package io.github.graphmaquina.codegen.source;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;

/**
 * Translates the type mirrors of a classpath binary into the {@link TypeUse}s of its header, and spells a mirror for
 * the owner token of a method's type parameters and for the class literals of an annotation. A mirror is resolved
 * already: it names each type by its canonical name and knows the declaration that owns each of its type variables.
 *
 * <p>{@link TreeTypeUses} reads the original sources into the same model from their syntax, unresolved, where only the
 * type parameters in scope tell a type variable from a type. The two produce one model from two different ones,
 * resolved types and syntax, so they share no reading.</p>
 */
final class MirrorTypeUses {

    private MirrorTypeUses() {
    }

    /** The use {@code mirror} is; every type it names is added to {@code referenced}. */
    static TypeUse use(TypeMirror mirror, Set<TypeElement> referenced) {
        switch (mirror.getKind()) {
            case DECLARED -> {
                DeclaredType declared = (DeclaredType) mirror;
                List<TypeUse> arguments = new ArrayList<>();
                for (TypeMirror argument : declared.getTypeArguments()) {
                    arguments.add(use(argument, referenced));
                }
                TypeMirror enclosing = declared.getEnclosingType();
                Optional<TypeUse> owner = enclosing.getKind() == TypeKind.DECLARED
                        ? Optional.of(use(enclosing, referenced))
                        : Optional.empty();
                TypeElement type = (TypeElement) declared.asElement();
                referenced.add(type);
                return new TypeUse.Declared(type.getQualifiedName().toString(), arguments, owner);
            }
            case TYPEVAR -> {
                TypeParameterElement parameter = (TypeParameterElement) ((TypeVariable) mirror).asElement();
                return new TypeUse.Variable(parameter.getSimpleName().toString(), key(parameter));
            }
            case ARRAY -> {
                return new TypeUse.Array(use(((ArrayType) mirror).getComponentType(), referenced));
            }
            case WILDCARD -> {
                WildcardType wildcard = (WildcardType) mirror;
                if (wildcard.getExtendsBound() != null) {
                    return new TypeUse.Wildcard(Optional.of(use(wildcard.getExtendsBound(), referenced)),
                            Optional.empty());
                }
                if (wildcard.getSuperBound() != null) {
                    return new TypeUse.Wildcard(Optional.empty(),
                            Optional.of(use(wildcard.getSuperBound(), referenced)));
                }
                return TypeUse.Wildcard.unbounded();
            }
            case BOOLEAN, BYTE, SHORT, INT, LONG, CHAR, FLOAT, DOUBLE, VOID -> {
                return new TypeUse.Primitive(mirror.toString());
            }
            default -> {
                return TypeUse.Declared.of(mirror.toString());
            }
        }
    }

    /** The identity of a type parameter, whether its generic owner is a type or a method declaration. */
    private static TypeParameterKey key(TypeParameterElement parameter) {
        Element generic = parameter.getGenericElement();
        if (generic instanceof TypeElement type) {
            return new TypeParameterKey(type.getQualifiedName().toString(),
                    position(type.getTypeParameters(), parameter));
        }
        ExecutableElement method = (ExecutableElement) generic;
        return new TypeParameterKey(methodOwner(method), position(method.getTypeParameters(), parameter));
    }

    private static String methodOwner(ExecutableElement method) {
        List<String> parameterTypes = new ArrayList<>();
        for (VariableElement parameter : method.getParameters()) {
            parameterTypes.add(spell(parameter.asType()));
        }
        return TypeParameterKey.methodOwner(
                ((TypeElement) method.getEnclosingElement()).getQualifiedName().toString(),
                method.getSimpleName().toString(), parameterTypes);
    }

    /** Type parameter names are unique inside their own declaration, so a name identifies the position. */
    private static int position(List<? extends TypeParameterElement> parameters,
            TypeParameterElement parameter) {
        for (int index = 0; index < parameters.size(); index++) {
            if (parameters.get(index).getSimpleName().contentEquals(parameter.getSimpleName())) {
                return index;
            }
        }
        return 0;
    }

    /**
     * Renders a mirror for a method owner token or an annotation's class literal; a type variable renders as its own
     * simple name.
     */
    static String spell(TypeMirror mirror) {
        switch (mirror.getKind()) {
            case DECLARED -> {
                DeclaredType declared = (DeclaredType) mirror;
                String name = ((TypeElement) declared.asElement()).getQualifiedName().toString();
                if (declared.getTypeArguments().isEmpty()) {
                    return name;
                }
                List<String> arguments = new ArrayList<>();
                for (TypeMirror argument : declared.getTypeArguments()) {
                    arguments.add(spell(argument));
                }
                return name + '<' + String.join(", ", arguments) + '>';
            }
            case TYPEVAR -> {
                return ((TypeVariable) mirror).asElement().getSimpleName().toString();
            }
            case ARRAY -> {
                return spell(((ArrayType) mirror).getComponentType()) + "[]";
            }
            case WILDCARD -> {
                WildcardType wildcard = (WildcardType) mirror;
                if (wildcard.getExtendsBound() != null) {
                    return "? extends " + spell(wildcard.getExtendsBound());
                }
                if (wildcard.getSuperBound() != null) {
                    return "? super " + spell(wildcard.getSuperBound());
                }
                return "?";
            }
            default -> {
                return mirror.toString();
            }
        }
    }
}
