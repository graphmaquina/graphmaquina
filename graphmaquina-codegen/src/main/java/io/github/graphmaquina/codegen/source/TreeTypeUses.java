package io.github.graphmaquina.codegen.source;

import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.WildcardTree;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Translates the type trees an original source spells into the {@link TypeUse}s of its headers, and spells them as
 * the declaration writes them. A tree is read unresolved: a simple name is a type variable exactly when
 * {@code variables} holds a type parameter of that name in scope, and every other name is kept as written.
 *
 * <p>{@link MirrorTypeUses} reads the binaries of the classpath into the same model from the type mirrors javac
 * resolved them into, which already know their type variables and canonical names. The two produce one model from two
 * different ones, syntax and resolved types, so they share no reading.</p>
 */
final class TreeTypeUses {

    private TreeTypeUses() {
    }

    static TypeUse type(Tree tree, Map<String, TypeParameterKey> variables) {
        switch (tree.getKind()) {
            case IDENTIFIER -> {
                String name = ((IdentifierTree) tree).getName().toString();
                TypeParameterKey key = variables.get(name);
                return key == null ? TypeUse.Declared.of(name) : new TypeUse.Variable(name, key);
            }
            case MEMBER_SELECT -> {
                return qualified((MemberSelectTree) tree, variables);
            }
            case PARAMETERIZED_TYPE -> {
                ParameterizedTypeTree parameterized = (ParameterizedTypeTree) tree;
                List<TypeUse> arguments = new ArrayList<>();
                for (Tree argument : parameterized.getTypeArguments()) {
                    arguments.add(type(argument, variables));
                }
                TypeUse base = type(parameterized.getType(), variables);
                return base instanceof TypeUse.Declared declared
                        ? new TypeUse.Declared(declared.name(), arguments, declared.owner())
                        : base;
            }
            case ARRAY_TYPE -> {
                return new TypeUse.Array(type(((ArrayTypeTree) tree).getType(), variables));
            }
            case PRIMITIVE_TYPE -> {
                return new TypeUse.Primitive(primitiveName((PrimitiveTypeTree) tree));
            }
            case EXTENDS_WILDCARD -> {
                return new TypeUse.Wildcard(
                        Optional.of(type(((WildcardTree) tree).getBound(), variables)), Optional.empty());
            }
            case SUPER_WILDCARD -> {
                return new TypeUse.Wildcard(Optional.empty(),
                        Optional.of(type(((WildcardTree) tree).getBound(), variables)));
            }
            case UNBOUNDED_WILDCARD -> {
                return TypeUse.Wildcard.unbounded();
            }
            case ANNOTATED_TYPE -> {
                return type(((AnnotatedTypeTree) tree).getUnderlyingType(), variables);
            }
            default -> {
                return TypeUse.Declared.of(spell(tree));
            }
        }
    }

    /**
     * The use a qualified name spells: a member type selected from a parameterized type, or from a member type selected
     * that way, keeps the type it was selected from as its owner; any other qualified name is a declared type, as
     * written.
     */
    private static TypeUse qualified(MemberSelectTree select, Map<String, TypeParameterKey> variables) {
        TypeUse selected = type(select.getExpression(), variables);
        if (selected instanceof TypeUse.Declared owner
                && (!owner.arguments().isEmpty() || owner.owner().isPresent())) {
            return new TypeUse.Declared(owner.name() + '.' + select.getIdentifier(), List.of(),
                    Optional.of(owner));
        }
        return TypeUse.Declared.of(spell(select));
    }

    /** Renders a type tree as the declaration spells it, which is how method owner tokens stay stable. */
    static String spell(Tree tree) {
        switch (tree.getKind()) {
            case IDENTIFIER -> {
                return ((IdentifierTree) tree).getName().toString();
            }
            case MEMBER_SELECT -> {
                MemberSelectTree select = (MemberSelectTree) tree;
                return spell(select.getExpression()) + '.' + select.getIdentifier();
            }
            case PARAMETERIZED_TYPE -> {
                ParameterizedTypeTree parameterized = (ParameterizedTypeTree) tree;
                List<String> arguments = new ArrayList<>();
                for (Tree argument : parameterized.getTypeArguments()) {
                    arguments.add(spell(argument));
                }
                return spell(parameterized.getType()) + '<' + String.join(", ", arguments) + '>';
            }
            case ARRAY_TYPE -> {
                return spell(((ArrayTypeTree) tree).getType()) + "[]";
            }
            case PRIMITIVE_TYPE -> {
                return primitiveName((PrimitiveTypeTree) tree);
            }
            case EXTENDS_WILDCARD -> {
                return "? extends " + spell(((WildcardTree) tree).getBound());
            }
            case SUPER_WILDCARD -> {
                return "? super " + spell(((WildcardTree) tree).getBound());
            }
            case UNBOUNDED_WILDCARD -> {
                return "?";
            }
            case ANNOTATED_TYPE -> {
                return spell(((AnnotatedTypeTree) tree).getUnderlyingType());
            }
            default -> {
                return tree.toString();
            }
        }
    }

    private static String primitiveName(PrimitiveTypeTree tree) {
        return tree.getPrimitiveTypeKind().toString().toLowerCase(Locale.ROOT);
    }
}
