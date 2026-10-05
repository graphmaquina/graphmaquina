package io.github.graphmaquina.codegen.source;

import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ArrayTypeTree;
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
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WildcardTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import io.github.graphmaquina.codegen.CodegenException;
import io.github.graphmaquina.codegen.GenerationRequest;
import io.github.graphmaquina.codegen.model.SourceLocation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Reads the consumer's original sources with {@link JavacTask#parse()} only. The declarations, modifiers,
 * types and annotations of a unit are read syntactically; bodies are never visited and no expression is
 * evaluated, so a source may name a type this generator has not produced yet. javac still parses every body,
 * so the sources are parsed at the release and in the encoding the compilation that follows reads them with:
 * a source may use the syntax of that release anywhere, and characters of that encoding.
 */
public final class SourceDeclarations {

    private static final String UNRESOLVED = "SG601";

    private SourceDeclarations() {
    }

    /**
     * Parses {@code files} at release {@value GenerationRequest#DEFAULT_RELEASE}, read in UTF-8, and anchors each
     * unit at its path relative to {@code project}, or at its absolute path for a file on another root. The file
     * manager is closed once the units are built; the parsed trees keep the syntax the declaration index still needs.
     */
    public static List<SourceUnit> parse(Path project, List<Path> files) {
        return List.copyOf(new LinkedHashSet<>(parseEach(project, files, GenerationRequest.DEFAULT_RELEASE,
                GenerationRequest.DEFAULT_ENCODING).values()));
    }

    /**
     * Parses {@code files} as the compilation reads them, at {@code release} and in {@code encoding}, and returns
     * the unit each of them was read into, in the order given. javac compares files by their real paths, so it reads
     * a file once however many paths lead to it through symbolic links, and those paths map to the unit of the
     * first; the hard links of one file have different real paths, so each is read into a unit of its own.
     */
    static Map<Path, SourceUnit> parseEach(Path project, List<Path> files, int release, Charset encoding) {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(files, "files");
        Objects.requireNonNull(encoding, "encoding");
        if (files.isEmpty()) {
            return Map.of();
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Map<Path, SourceUnit> units = new LinkedHashMap<>();
        try (StandardJavaFileManager fileManager =
                compiler.getStandardFileManager(diagnostics, Locale.ROOT, encoding)) {
            List<JavaFileObject> sources = new ArrayList<>();
            for (JavaFileObject source : fileManager.getJavaFileObjectsFromPaths(files)) {
                sources.add(source);
            }
            JavacTask task = (JavacTask) compiler.getTask(null, fileManager, diagnostics,
                    List.of("-proc:none", "--release", Integer.toString(release)), null, sources);
            try {
                // A file object equals every other one of the same file, which is how javac read it only once.
                Map<JavaFileObject, SourceUnit> parsed = new HashMap<>();
                for (CompilationUnitTree tree : task.parse()) {
                    parsed.put(tree.getSourceFile(), new SourceUnit(tree, fileOrigin(project, tree)));
                }
                for (int index = 0; index < files.size(); index++) {
                    units.putIfAbsent(files.get(index), parsed.get(sources.get(index)));
                }
            } finally {
                // javac's own error is the one the user can act on, so it replaces whatever parsing threw.
                requireNoErrors("the original sources cannot be read", diagnostics, Optional.of(project));
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("cannot read the original sources under " + project, failure);
        }
        return Collections.unmodifiableMap(units);
    }

    /** The package {@code unit} declares, as its declaration spells it; empty for the unnamed package. */
    static String packageName(SourceUnit unit) {
        ExpressionTree packageName = unit.tree().getPackageName();
        return packageName == null ? "" : spell(packageName);
    }

    /** The qualified names of the top-level types {@code unit} declares, in declaration order. */
    static List<String> topLevelTypes(SourceUnit unit) {
        String packageName = packageName(unit);
        List<String> types = new ArrayList<>();
        for (Tree declaration : unit.tree().getTypeDecls()) {
            if (declaration instanceof ClassTree type) {
                types.add(packageName.isEmpty()
                        ? type.getSimpleName().toString()
                        : packageName + '.' + type.getSimpleName());
            }
        }
        return List.copyOf(types);
    }

    /**
     * Fails when javac reported an error for one run, naming the javac message and, when the error is in a file and
     * {@code project} is given, that file and the position. A tooling failure stops the task: a truncated tree is
     * never read as if it were a valid declaration. Only the first error is described, followed by the number of
     * further errors of the same run.
     *
     * @param project the directory diagnostics are anchored against, empty when the run has no original
     *     sources of its own
     */
    static void requireNoErrors(String what, DiagnosticCollector<JavaFileObject> diagnostics,
            Optional<Path> project) {
        List<Diagnostic<? extends JavaFileObject>> errors = diagnostics.getDiagnostics().stream()
                .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                .toList();
        if (errors.isEmpty()) {
            return;
        }
        Diagnostic<? extends JavaFileObject> first = errors.get(0);
        Optional<SourceLocation> location = location(first, project);
        StringBuilder message = new StringBuilder(what).append(": ")
                .append(location.map(anchor -> anchor.path() + ':' + anchor.line() + ": ").orElse(""))
                .append(first.getMessage(Locale.ROOT));
        if (errors.size() > 1) {
            message.append(" (and ").append(errors.size() - 1).append(" further javac error")
                    .append(errors.size() == 2 ? "" : "s").append(')');
        }
        String reported = message.toString();
        throw location.map(anchor -> new CodegenException(UNRESOLVED, reported, anchor))
                .orElseGet(() -> new CodegenException(UNRESOLVED, reported));
    }

    private static Optional<SourceLocation> location(Diagnostic<? extends JavaFileObject> diagnostic,
            Optional<Path> project) {
        JavaFileObject source = diagnostic.getSource();
        if (source == null || project.isEmpty() || !"file".equals(source.toUri().getScheme())) {
            return Optional.empty();
        }
        return Optional.of(new SourceLocation(relative(project.get(), Path.of(source.toUri())),
                Math.max(diagnostic.getLineNumber(), 1L), Math.max(diagnostic.getColumnNumber(), 1L)));
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
        for (TypeParameterTree parameter : type.getTypeParameters()) {
            variables.put(parameter.getName().toString(),
                    new TypeParameterKey(canonicalName, typeParameters.size()));
            typeParameters.add(parameter.getName().toString());
        }

        List<TypeUse> supertypes = new ArrayList<>();
        if (type.getExtendsClause() != null) {
            supertypes.add(type(type.getExtendsClause(), variables));
        }
        for (Tree implemented : type.getImplementsClause()) {
            supertypes.add(type(implemented, variables));
        }

        List<TypeHeader.Member> members = new ArrayList<>();
        List<String> constants = new ArrayList<>();
        for (Tree member : type.getMembers()) {
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
                supertypes, members, markers(unit, positions, type.getModifiers()), constants, scope,
                Optional.of(location(unit, positions, type))));
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
            spelledParameters.add(spell(parameter.getType()));
        }
        String owner = TypeParameterKey.methodOwner(declaringType, method.getName().toString(),
                spelledParameters);
        Map<String, TypeParameterKey> variables = new LinkedHashMap<>(enclosing);
        List<String> typeParameters = new ArrayList<>();
        for (TypeParameterTree parameter : method.getTypeParameters()) {
            variables.put(parameter.getName().toString(),
                    new TypeParameterKey(owner, typeParameters.size()));
            typeParameters.add(parameter.getName().toString());
        }
        List<TypeUse> parameterTypes = new ArrayList<>();
        for (VariableTree parameter : method.getParameters()) {
            parameterTypes.add(type(parameter.getType(), variables));
        }
        return new TypeHeader.Member(method.getName().toString(), ElementKind.METHOD,
                method.getModifiers().getFlags(), typeParameters, parameterTypes,
                type(method.getReturnType(), variables), markers(unit, positions, method.getModifiers()),
                Optional.of(location(unit, positions, method)));
    }

    private static TypeHeader.Member field(SourceUnit unit, SourcePositions positions, VariableTree field,
            Map<String, TypeParameterKey> variables) {
        return new TypeHeader.Member(field.getName().toString(), ElementKind.FIELD,
                field.getModifiers().getFlags(), List.of(), List.of(), type(field.getType(), variables),
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
            markers.add(new TypeHeader.Marker(spell(annotation.getAnnotationType()), classLiterals,
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
                    classLiterals.add(spell(select.getExpression()));
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

    private static TypeUse type(Tree tree, Map<String, TypeParameterKey> variables) {
        switch (tree.getKind()) {
            case IDENTIFIER -> {
                String name = ((IdentifierTree) tree).getName().toString();
                TypeParameterKey key = variables.get(name);
                return key == null ? TypeUse.Declared.of(name) : new TypeUse.Variable(name, key);
            }
            case MEMBER_SELECT -> {
                MemberSelectTree select = (MemberSelectTree) tree;
                TypeUse selected = type(select.getExpression(), variables);
                if (selected instanceof TypeUse.Declared owner
                        && (!owner.arguments().isEmpty() || owner.owner().isPresent())) {
                    return new TypeUse.Declared(owner.name() + '.' + select.getIdentifier(), List.of(),
                            Optional.of(owner));
                }
                return TypeUse.Declared.of(spell(tree));
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

    /** Renders a type tree as the declaration spells it, which is how method owner tokens stay stable. */
    private static String spell(Tree tree) {
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
            String spelled = spell(imported.getQualifiedIdentifier());
            if (spelled.endsWith(".*")) {
                onDemandImports.add(spelled.substring(0, spelled.length() - 2));
            } else {
                singleImports.add(spelled);
            }
        }
        ExpressionTree packageName = tree.getPackageName();
        return new TypeHeader.Scope(packageName == null ? "" : spell(packageName), singleImports,
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

    private static SourceLocation fileOrigin(Path project, CompilationUnitTree tree) {
        return new SourceLocation(relative(project, Path.of(tree.getSourceFile().toUri())), 1L, 1L);
    }

    /**
     * The path of {@code file} relative to {@code project}, written with forward slashes on every platform so the
     * export index that publishes it does not depend on the machine that built it. A file the project directory
     * cannot be relativized against, such as one on another drive, keeps its absolute path.
     */
    static String relative(Path project, Path file) {
        Path target = file.toAbsolutePath().normalize();
        Path shown;
        try {
            shown = project.toAbsolutePath().normalize().relativize(target);
        } catch (IllegalArgumentException differentRoot) {
            shown = target;
        }
        return portable(shown.toString(), shown.getFileSystem().getSeparator());
    }

    /** {@code path} with every {@code separator} of the file system it was read from replaced by a forward slash. */
    static String portable(String path, String separator) {
        return path.replace(separator, "/");
    }
}
