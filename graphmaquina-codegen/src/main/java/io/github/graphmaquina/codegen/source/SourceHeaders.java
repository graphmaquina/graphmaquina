package io.github.graphmaquina.codegen.source;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import io.github.graphmaquina.codegen.model.SourceLocation;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.tools.Diagnostic;

/**
 * Reads the header of every type a parsed unit declares from its syntax tree, for {@link SourceDeclarations}, which
 * says what is read and which bodies are visited; the types are read through {@link TreeTypeUses}.
 */
final class SourceHeaders {

    private SourceHeaders() {
    }

    /**
     * Reads every type declaration of {@code unit}, nested declarations included: each one after the declarations
     * nested in it, and otherwise in source order. Each header carries the modifiers written on the declaration
     * plus the {@code public} and {@code static} the language implies for a nested one.
     */
    static List<TypeHeader> declarations(SourceUnit unit, SourcePositions positions) {
        TypeHeader.Scope scope = scope(unit.tree());
        List<TypeHeader> headers = new ArrayList<>();
        for (Tree declaration : unit.tree().getTypeDecls()) {
            if (declaration instanceof ClassTree type) {
                read(unit, positions, scope, scope.packageName(), Optional.empty(), type, Map.of(), headers);
            }
        }
        return List.copyOf(headers);
    }

    private static void read(SourceUnit unit, SourcePositions positions, TypeHeader.Scope scope,
            String enclosing, Optional<ElementKind> enclosingKind, ClassTree type,
            Map<String, TypeParameterKey> inherited, List<TypeHeader> headers) {
        String canonicalName = enclosing.isEmpty()
                ? type.getSimpleName().toString()
                : enclosing + '.' + type.getSimpleName();
        Map<String, TypeParameterKey> variables = new LinkedHashMap<>(inherited);
        List<String> typeParameters = new ArrayList<>();
        declareTypeParameters(canonicalName, type.getTypeParameters(), variables, typeParameters);

        List<TypeUse> supertypes = new ArrayList<>();
        if (type.getExtendsClause() != null) {
            supertypes.add(TreeTypeUses.type(type.getExtendsClause(), variables));
        }
        for (Tree implemented : type.getImplementsClause()) {
            supertypes.add(TreeTypeUses.type(implemented, variables));
        }

        List<TypeHeader.Member> members = new ArrayList<>();
        List<TypeHeader.LocalMarker> localMarkers = new ArrayList<>();
        List<String> constants = new ArrayList<>();
        for (Tree member : type.getMembers()) {
            if (!(member instanceof ClassTree)) {
                localMarkers.addAll(localMarkers(unit, positions, type, member));
            }
            if (member instanceof ClassTree nested) {
                read(unit, positions, scope, canonicalName, Optional.of(kind(type)), nested, variables, headers);
            } else if (member instanceof MethodTree method) {
                if (method.getReturnType() != null) {
                    members.add(method(unit, positions, canonicalName, method, variables));
                }
            } else if (member instanceof VariableTree field) {
                if (isEnumConstant(type, field)) {
                    constants.add(field.getName().toString());
                } else {
                    members.add(field(unit, positions, field, variables));
                }
            }
        }

        headers.add(new TypeHeader(canonicalName, kind(type), modifiers(type, enclosingKind), typeParameters,
                supertypes, members, markers(unit, positions, type.getModifiers()), localMarkers, constants, scope,
                Optional.of(location(unit, positions, type))));
    }

    /**
     * Brings the type parameters of the declaration {@code owner} names into scope, in declaration order: each one is
     * added to {@code variables} under its name, keyed by its position, the number of {@code names} before it, and
     * then its name is added to {@code names}.
     */
    private static void declareTypeParameters(String owner, List<? extends TypeParameterTree> parameters,
            Map<String, TypeParameterKey> variables, List<String> names) {
        for (TypeParameterTree parameter : parameters) {
            variables.put(parameter.getName().toString(),
                    new TypeParameterKey(owner, names.size()));
            names.add(parameter.getName().toString());
        }
    }

    /**
     * The annotations written in the local and anonymous classes {@code member} of {@code owner} declares, at any
     * depth, in its body, its initializer or, for an enum constant, its class body: on such a class and on its methods
     * and fields. Each keeps the class bodies between {@code member} and it, and the local classes {@code member}
     * declares, which decide what its name means. Nothing else of the member is read.
     */
    private static List<TypeHeader.LocalMarker> localMarkers(SourceUnit unit, SourcePositions positions,
            ClassTree owner, Tree member) {
        LocalClasses classes = new LocalClasses(unit, positions);
        if (member instanceof VariableTree field && isEnumConstant(owner, field)) {
            classes.constant((NewClassTree) field.getInitializer(), List.of());
        } else {
            classes.scan(member, List.of());
        }
        List<TypeHeader.LocalMarker> found = new ArrayList<>();
        for (int at = 0; at < classes.markers.size(); at++) {
            found.add(new TypeHeader.LocalMarker(classes.markers.get(at), classes.scopes.get(at), classes.names));
        }
        return found;
    }

    /**
     * The modifiers written on {@code type} plus the ones the language implies for it, as javac records them for
     * a binary: a member type of an interface is public and static (JLS 9.5), and a nested enum, record or
     * interface is static (JLS 8.9, 8.10, 9.1.1.3). None of those is an inner class, so none of their uses
     * captures the parameterization of an enclosing type.
     */
    private static Set<Modifier> modifiers(ClassTree type, Optional<ElementKind> enclosingKind) {
        Set<Modifier> modifiers = EnumSet.noneOf(Modifier.class);
        modifiers.addAll(type.getModifiers().getFlags());
        if (enclosingKind.isPresent()) {
            if (enclosingKind.get() == ElementKind.INTERFACE || enclosingKind.get() == ElementKind.ANNOTATION_TYPE) {
                modifiers.add(Modifier.PUBLIC);
                modifiers.add(Modifier.STATIC);
            } else if (kind(type) != ElementKind.CLASS) {
                modifiers.add(Modifier.STATIC);
            }
        }
        return modifiers;
    }

    private static TypeHeader.Member method(SourceUnit unit, SourcePositions positions, String declaringType,
            MethodTree method, Map<String, TypeParameterKey> enclosing) {
        List<String> spelledParameters = new ArrayList<>();
        for (VariableTree parameter : method.getParameters()) {
            spelledParameters.add(TreeTypeUses.spell(parameter.getType()));
        }
        String owner = TypeParameterKey.methodOwner(declaringType, method.getName().toString(),
                spelledParameters);
        Map<String, TypeParameterKey> variables = new LinkedHashMap<>(enclosing);
        List<String> typeParameters = new ArrayList<>();
        declareTypeParameters(owner, method.getTypeParameters(), variables, typeParameters);
        List<TypeUse> parameterTypes = new ArrayList<>();
        for (VariableTree parameter : method.getParameters()) {
            parameterTypes.add(TreeTypeUses.type(parameter.getType(), variables));
        }
        return new TypeHeader.Member(method.getName().toString(), ElementKind.METHOD,
                method.getModifiers().getFlags(), typeParameters, parameterTypes,
                TreeTypeUses.type(method.getReturnType(), variables), markers(unit, positions, method.getModifiers()),
                Optional.of(location(unit, positions, method)));
    }

    private static TypeHeader.Member field(SourceUnit unit, SourcePositions positions, VariableTree field,
            Map<String, TypeParameterKey> variables) {
        return new TypeHeader.Member(field.getName().toString(), ElementKind.FIELD,
                field.getModifiers().getFlags(), List.of(), List.of(), TreeTypeUses.type(field.getType(), variables),
                markers(unit, positions, field.getModifiers()), Optional.of(location(unit, positions, field)));
    }

    private static List<TypeHeader.Marker> markers(SourceUnit unit, SourcePositions positions,
            ModifiersTree modifiers) {
        List<TypeHeader.Marker> markers = new ArrayList<>();
        for (AnnotationTree annotation : modifiers.getAnnotations()) {
            List<String> classLiterals = new ArrayList<>();
            for (ExpressionTree argument : annotation.getArguments()) {
                classLiterals(argument, classLiterals);
            }
            markers.add(new TypeHeader.Marker(TreeTypeUses.spell(annotation.getAnnotationType()), classLiterals,
                    Optional.of(location(unit, positions, annotation))));
        }
        return markers;
    }

    /** Collects the {@code X.class} arguments of an annotation without evaluating any other expression. */
    private static void classLiterals(ExpressionTree expression, List<String> classLiterals) {
        switch (expression.getKind()) {
            case ASSIGNMENT -> classLiterals(((AssignmentTree) expression).getExpression(), classLiterals);
            case NEW_ARRAY -> {
                List<? extends ExpressionTree> elements = ((NewArrayTree) expression).getInitializers();
                if (elements != null) {
                    for (ExpressionTree element : elements) {
                        classLiterals(element, classLiterals);
                    }
                }
            }
            case MEMBER_SELECT -> {
                MemberSelectTree select = (MemberSelectTree) expression;
                if (select.getIdentifier().contentEquals("class")) {
                    classLiterals.add(TreeTypeUses.spell(select.getExpression()));
                }
            }
            default -> {
            }
        }
    }

    /**
     * A member of an enum declaration is a constant when it is the synthesized field the parser produces for
     * one: its declared type is the enum's own simple name and its initializer is a class instantiation, which
     * no ordinary field of an enum can be.
     */
    private static boolean isEnumConstant(ClassTree owner, VariableTree field) {
        return owner.getKind() == Tree.Kind.ENUM
                && field.getInitializer() != null
                && field.getInitializer().getKind() == Tree.Kind.NEW_CLASS
                && field.getType() instanceof IdentifierTree declared
                && declared.getName().contentEquals(owner.getSimpleName());
    }

    private static ElementKind kind(ClassTree type) {
        return switch (type.getKind()) {
            case INTERFACE -> ElementKind.INTERFACE;
            case ENUM -> ElementKind.ENUM;
            case RECORD -> ElementKind.RECORD;
            case ANNOTATION_TYPE -> ElementKind.ANNOTATION_TYPE;
            default -> ElementKind.CLASS;
        };
    }

    private static TypeHeader.Scope scope(CompilationUnitTree tree) {
        List<String> singleImports = new ArrayList<>();
        List<String> onDemandImports = new ArrayList<>();
        for (ImportTree imported : tree.getImports()) {
            String spelled = TreeTypeUses.spell(imported.getQualifiedIdentifier());
            if (spelled.endsWith(".*")) {
                onDemandImports.add(spelled.substring(0, spelled.length() - 2));
            } else {
                singleImports.add(spelled);
            }
        }
        ExpressionTree packageName = tree.getPackageName();
        return new TypeHeader.Scope(packageName == null ? "" : TreeTypeUses.spell(packageName), singleImports,
                onDemandImports);
    }

    private static SourceLocation location(SourceUnit unit, SourcePositions positions, Tree tree) {
        long start = positions.getStartPosition(unit.tree(), tree);
        LineMap lineMap = unit.tree().getLineMap();
        if (start == Diagnostic.NOPOS || lineMap == null) {
            return unit.origin();
        }
        return new SourceLocation(unit.origin().path(), lineMap.getLineNumber(start),
                lineMap.getColumnNumber(start));
    }

    /**
     * Reads the local and anonymous classes of one member, each scan given the class bodies that enclose the trees it
     * reads, innermost first: every annotation written on such a class or on one of its methods and fields, with the
     * class bodies that enclose it, and the name of every local class, whatever block declares it.
     */
    private static final class LocalClasses extends TreeScanner<Void, List<TypeHeader.LocalScope>> {

        private final SourceUnit unit;
        private final SourcePositions positions;
        private final List<TypeHeader.Marker> markers = new ArrayList<>();
        /** The class bodies that enclose each of {@link #markers}, at the same position. */
        private final List<List<TypeHeader.LocalScope>> scopes = new ArrayList<>();
        private final Set<String> names = new LinkedHashSet<>();

        private LocalClasses(SourceUnit unit, SourcePositions positions) {
            this.unit = unit;
            this.positions = positions;
        }

        @Override
        public Void visitClass(ClassTree local, List<TypeHeader.LocalScope> enclosing) {
            names.add(local.getSimpleName().toString());
            body(local, Optional.of(supertypes(local)), enclosing);
            return null;
        }

        @Override
        public Void visitNewClass(NewClassTree creation, List<TypeHeader.LocalScope> enclosing) {
            if (creation.getClassBody() == null) {
                return super.visitNewClass(creation, enclosing);
            }
            scan(creation.getEnclosingExpression(), enclosing);
            scan(creation.getArguments(), enclosing);
            body(creation.getClassBody(), creation.getEnclosingExpression() == null
                    ? Optional.of(List.of(erasure(creation.getIdentifier())))
                    : Optional.empty(), enclosing);
            return null;
        }

        /** The arguments and the class body of an enum constant, which spells no supertype of its own. */
        private void constant(NewClassTree creation, List<TypeHeader.LocalScope> enclosing) {
            scan(creation.getArguments(), enclosing);
            if (creation.getClassBody() != null) {
                body(creation.getClassBody(), Optional.of(List.of()), enclosing);
            }
        }

        /**
         * One class and everything it declares: the annotations on the class, read in {@code enclosing}, then the
         * ones on its methods and fields and the classes its members declare, read in its own body too.
         */
        private void body(ClassTree type, Optional<List<String>> supertypes, List<TypeHeader.LocalScope> enclosing) {
            add(type.getModifiers(), enclosing);
            List<String> memberTypes = new ArrayList<>();
            for (Tree declared : type.getMembers()) {
                if (declared instanceof ClassTree nested) {
                    memberTypes.add(nested.getSimpleName().toString());
                }
            }
            List<TypeHeader.LocalScope> inside = new ArrayList<>();
            inside.add(new TypeHeader.LocalScope(memberTypes, supertypes));
            inside.addAll(enclosing);
            for (Tree declared : type.getMembers()) {
                if (declared instanceof MethodTree method) {
                    add(method.getModifiers(), inside);
                } else if (declared instanceof VariableTree field) {
                    add(field.getModifiers(), inside);
                }
            }
            for (Tree declared : type.getMembers()) {
                if (declared instanceof ClassTree nested) {
                    body(nested, Optional.of(supertypes(nested)), inside);
                } else if (declared instanceof VariableTree field && isEnumConstant(type, field)) {
                    constant((NewClassTree) field.getInitializer(), inside);
                } else {
                    scan(declared, inside);
                }
            }
        }

        private void add(ModifiersTree modifiers, List<TypeHeader.LocalScope> enclosing) {
            for (TypeHeader.Marker marker : markers(unit, positions, modifiers)) {
                markers.add(marker);
                scopes.add(List.copyOf(enclosing));
            }
        }

        /** The supertypes {@code type} spells in its extends and implements clauses, without type arguments. */
        private static List<String> supertypes(ClassTree type) {
            List<String> supertypes = new ArrayList<>();
            if (type.getExtendsClause() != null) {
                supertypes.add(erasure(type.getExtendsClause()));
            }
            for (Tree implemented : type.getImplementsClause()) {
                supertypes.add(erasure(implemented));
            }
            return supertypes;
        }

        /** {@code tree} as spelled without type arguments, so {@code Outer<String>.Inner} as {@code Outer.Inner}. */
        private static String erasure(Tree tree) {
            return TreeTypeUses.type(tree, Map.of()) instanceof TypeUse.Declared declared
                    ? declared.name()
                    : TreeTypeUses.spell(tree);
        }
    }
}
