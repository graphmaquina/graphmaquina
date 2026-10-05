package io.github.graphmaquina.codegen.source;

import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
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
import javax.lang.model.util.Elements;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

/**
 * Materializes classpath headers into the same declaration model as source headers, keeping generic owner
 * identity, modifiers and direct supertypes.
 *
 * <p>The javac task behind it compiles one synthetic class held in memory, never written anywhere and never
 * published, over an empty source path. The consumer's original sources are deliberately not visible to it,
 * so it can only complete declarations that already exist as binaries, and it never loads or initializes an
 * application class.</p>
 */
final class ClasspathDeclarations implements AutoCloseable {

    private static final String PROBE_NAME = "SgClasspathProbe";

    private final StandardJavaFileManager fileManager;
    private final Elements elements;
    private final SourcePositions sourcePositions;
    private final Map<String, Optional<TypeHeader>> headers = new HashMap<>();

    private ClasspathDeclarations(StandardJavaFileManager fileManager, Elements elements,
            SourcePositions sourcePositions) {
        this.fileManager = fileManager;
        this.elements = elements;
        this.sourcePositions = sourcePositions;
    }

    /** Opens a task over {@code classpath} whose element model stays usable until {@link #close()}. */
    static ClasspathDeclarations open(List<Path> classpath) {
        Objects.requireNonNull(classpath, "classpath");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager fileManager =
                compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8);
        try {
            fileManager.setLocationFromPaths(StandardLocation.SOURCE_PATH, List.of());
            fileManager.setLocationFromPaths(StandardLocation.CLASS_PATH, classpath);
            JavacTask task = (JavacTask) compiler.getTask(null, fileManager, diagnostics,
                    List.of("-proc:none", "--release", "21"), null, List.of(probe()));
            try {
                task.analyze();
            } finally {
                // A broken classpath entry makes javac fail on top of its own diagnostic; report the
                // diagnostic, which names the entry and the cause, rather than the failure it cascades into.
                SourceDeclarations.requireNoErrors("the classpath headers cannot be read", diagnostics,
                        Optional.empty());
            }
            return new ClasspathDeclarations(fileManager, task.getElements(),
                    Trees.instance(task).getSourcePositions());
        } catch (IOException failure) {
            closeQuietly(fileManager, failure);
            throw new UncheckedIOException("cannot open the classpath headers", failure);
        } catch (RuntimeException failure) {
            closeQuietly(fileManager, failure);
            throw failure;
        }
    }

    /** The header of {@code canonicalName} when the classpath declares it under exactly that name. */
    Optional<TypeHeader> find(String canonicalName) {
        Optional<TypeHeader> cached = headers.get(canonicalName);
        if (cached != null) {
            return cached;
        }
        Optional<TypeHeader> resolved = materialize(canonicalName);
        headers.put(canonicalName, resolved);
        return resolved;
    }

    /**
     * The package that declares the classpath type {@code canonicalName}, empty for the unnamed package and for
     * a name the classpath does not declare. A classpath header spells every name canonically, so its scope
     * carries no package; access checks read the package here instead.
     */
    String packageOf(String canonicalName) {
        TypeElement element = canonicalName.isEmpty() || canonicalName.equals(PROBE_NAME)
                ? null
                : elements.getTypeElement(canonicalName);
        return element == null ? "" : elements.getPackageOf(element).getQualifiedName().toString();
    }

    /**
     * The position service used to anchor original declarations. A start position is data the parsed tree
     * carries, so the task that serves it does not have to be the task that parsed the tree.
     */
    SourcePositions sourcePositions() {
        return sourcePositions;
    }

    @Override
    public void close() {
        try {
            fileManager.close();
        } catch (IOException failure) {
            throw new UncheckedIOException("cannot close the classpath headers", failure);
        }
    }

    private Optional<TypeHeader> materialize(String canonicalName) {
        if (canonicalName.isEmpty() || canonicalName.equals(PROBE_NAME)) {
            return Optional.empty();
        }
        TypeElement element = elements.getTypeElement(canonicalName);
        return element == null ? Optional.empty() : Optional.of(header(element));
    }

    private static TypeHeader header(TypeElement element) {
        List<String> typeParameters = new ArrayList<>();
        for (TypeParameterElement parameter : element.getTypeParameters()) {
            typeParameters.add(parameter.getSimpleName().toString());
        }
        List<TypeUse> supertypes = new ArrayList<>();
        if (element.getSuperclass().getKind() == TypeKind.DECLARED) {
            supertypes.add(use(element.getSuperclass()));
        }
        for (TypeMirror implemented : element.getInterfaces()) {
            supertypes.add(use(implemented));
        }
        List<TypeHeader.Member> members = new ArrayList<>();
        List<String> constants = new ArrayList<>();
        for (Element enclosed : element.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.ENUM_CONSTANT) {
                constants.add(enclosed.getSimpleName().toString());
            } else if (enclosed.getKind() == ElementKind.FIELD) {
                members.add(field((VariableElement) enclosed));
            } else if (enclosed.getKind() == ElementKind.METHOD) {
                members.add(method((ExecutableElement) enclosed));
            }
        }
        return new TypeHeader(element.getQualifiedName().toString(), element.getKind(),
                element.getModifiers(), typeParameters, supertypes, members, markers(element), constants,
                TypeHeader.Scope.BINARY, Optional.empty());
    }

    private static TypeHeader.Member method(ExecutableElement method) {
        List<String> typeParameters = new ArrayList<>();
        for (TypeParameterElement parameter : method.getTypeParameters()) {
            typeParameters.add(parameter.getSimpleName().toString());
        }
        List<TypeUse> parameterTypes = new ArrayList<>();
        for (VariableElement parameter : method.getParameters()) {
            parameterTypes.add(use(parameter.asType()));
        }
        return new TypeHeader.Member(method.getSimpleName().toString(), ElementKind.METHOD,
                method.getModifiers(), typeParameters, parameterTypes, use(method.getReturnType()),
                markers(method), Optional.empty());
    }

    private static TypeHeader.Member field(VariableElement field) {
        return new TypeHeader.Member(field.getSimpleName().toString(), ElementKind.FIELD,
                field.getModifiers(), List.of(), List.of(), use(field.asType()), markers(field),
                Optional.empty());
    }

    private static List<TypeHeader.Marker> markers(Element element) {
        List<TypeHeader.Marker> markers = new ArrayList<>();
        for (AnnotationMirror annotation : element.getAnnotationMirrors()) {
            List<String> classLiterals = new ArrayList<>();
            for (AnnotationValue value : annotation.getElementValues().values()) {
                classLiterals(value, classLiterals);
            }
            markers.add(new TypeHeader.Marker(
                    ((TypeElement) annotation.getAnnotationType().asElement()).getQualifiedName().toString(),
                    classLiterals, Optional.empty()));
        }
        return markers;
    }

    private static void classLiterals(AnnotationValue value, List<String> classLiterals) {
        Object raw = value.getValue();
        if (raw instanceof TypeMirror mirror) {
            classLiterals.add(spell(mirror));
        } else if (raw instanceof List<?> elements) {
            for (Object element : elements) {
                if (element instanceof AnnotationValue nested) {
                    classLiterals(nested, classLiterals);
                }
            }
        }
    }

    private static TypeUse use(TypeMirror mirror) {
        switch (mirror.getKind()) {
            case DECLARED -> {
                DeclaredType declared = (DeclaredType) mirror;
                List<TypeUse> arguments = new ArrayList<>();
                for (TypeMirror argument : declared.getTypeArguments()) {
                    arguments.add(use(argument));
                }
                TypeMirror enclosing = declared.getEnclosingType();
                Optional<TypeUse> owner = enclosing.getKind() == TypeKind.DECLARED
                        ? Optional.of(use(enclosing))
                        : Optional.empty();
                return new TypeUse.Declared(
                        ((TypeElement) declared.asElement()).getQualifiedName().toString(), arguments, owner);
            }
            case TYPEVAR -> {
                TypeParameterElement parameter = (TypeParameterElement) ((TypeVariable) mirror).asElement();
                return new TypeUse.Variable(parameter.getSimpleName().toString(), key(parameter));
            }
            case ARRAY -> {
                return new TypeUse.Array(use(((ArrayType) mirror).getComponentType()));
            }
            case WILDCARD -> {
                WildcardType wildcard = (WildcardType) mirror;
                if (wildcard.getExtendsBound() != null) {
                    return new TypeUse.Wildcard(Optional.of(use(wildcard.getExtendsBound())), Optional.empty());
                }
                if (wildcard.getSuperBound() != null) {
                    return new TypeUse.Wildcard(Optional.empty(), Optional.of(use(wildcard.getSuperBound())));
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

    /** Renders a mirror for a method owner token; a type variable renders as its own simple name. */
    private static String spell(TypeMirror mirror) {
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

    private static JavaFileObject probe() {
        return new SimpleJavaFileObject(URI.create("string:///" + PROBE_NAME + ".java"),
                JavaFileObject.Kind.SOURCE) {

            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return "final class " + PROBE_NAME + " {}";
            }
        };
    }

    private static void closeQuietly(StandardJavaFileManager fileManager, Throwable opening) {
        try {
            fileManager.close();
        } catch (IOException closing) {
            opening.addSuppressed(closing);
        }
    }
}
