package io.github.graphmaquina.codegen.source;

import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;
import io.github.graphmaquina.codegen.GenerationRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.ModuleElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Materializes classpath headers into the same declaration model as source headers, keeping generic owner
 * identity, modifiers and direct supertypes.
 *
 * <p>The javac task behind it compiles one synthetic class held in memory, never written anywhere and never
 * published, over an empty source path. The consumer's original sources are deliberately not visible to it,
 * so it can only complete declarations that already exist as binaries, and it never loads or initializes an
 * application class.</p>
 *
 * <p>One canonical name can name two types of the classpath: a type of a named package, such as
 * {@code shop.Model.Order} of the package {@code shop}, and a member type of a class of the unnamed package named
 * like the first segment of that package, such as the member {@code Model.Order} of a class {@code shop}. javac admits
 * both. {@link Elements#getTypeElement} finds a name in javac's table of the classes it has entered, keyed by flat
 * name, which is the canonical name of a top-level type only. Any other name, such as the canonical name of every
 * member type and of every top-level type javac has not entered yet, it resolves as code of the unnamed package spells
 * it, where such a class obscures the package (JLS 6.4.2), so it can answer the member type of the class, or no type
 * at all, for a type of the package. So {@link #named} and {@link #unnamed} each answer a name with a type of one of
 * the two packages only, and every header keeps the package that declares it.</p>
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
final class ClasspathDeclarations implements AutoCloseable {

    private static final String PROBE_NAME = "SgClasspathProbe";

    private final StandardJavaFileManager fileManager;
    private final Elements elements;

    /**
     * The position service used to anchor original declarations. A start position is data the parsed tree
     * carries, so the task that serves it does not have to be the task that parsed the tree.
     */
    @Getter(AccessLevel.PACKAGE)
    private final SourcePositions sourcePositions;
    private final Map<String, Optional<TypeHeader>> namedHeaders = new HashMap<>();
    private final Map<String, Optional<TypeHeader>> unnamedHeaders = new HashMap<>();
    /** The package of every header materialized here, by identity, since two headers can be equal records. */
    private final Map<TypeHeader, String> packages = new IdentityHashMap<>();
    /**
     * For every header of the unnamed package materialized here, by identity: the canonical names of the types its
     * header names that belong to a named package, and of those that belong to the unnamed package, as
     * {@link #materialize} collects them.
     */
    private final Map<TypeHeader, Set<String>> namedReferences = new IdentityHashMap<>();
    private final Map<TypeHeader, Set<String>> unnamedReferences = new IdentityHashMap<>();

    /**
     * Opens a task over {@code classpath} at release {@value GenerationRequest#DEFAULT_RELEASE}, whose element
     * model stays usable until {@link #close()}.
     */
    static ClasspathDeclarations open(List<Path> classpath) {
        return open(classpath, GenerationRequest.DEFAULT_RELEASE, GenerationRequest.DEFAULT_ENCODING);
    }

    /**
     * Opens a task over {@code classpath} whose element model stays usable until {@link #close()}. It resolves the
     * JDK types at {@code release}, as the compilation that follows resolves them, so a type the JDK added after
     * an older release is a JDK type here exactly when it is one for javac; {@code encoding} is the one the
     * original sources are read in, although this task reads none.
     */
    static ClasspathDeclarations open(@NonNull List<Path> classpath, int release, @NonNull Charset encoding) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, encoding);
        try {
            fileManager.setLocationFromPaths(StandardLocation.SOURCE_PATH, List.of());
            fileManager.setLocationFromPaths(StandardLocation.CLASS_PATH, classpath);
            JavacTask task = (JavacTask) compiler.getTask(null, fileManager, diagnostics,
                    List.of("-proc:none", "--release", Integer.toString(release)), null, List.of(probe()));
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

    /**
     * The header of {@code canonicalName} when the classpath declares a type under exactly that name: the type of a
     * named package when there is one, as {@link #named} finds it, and otherwise the type of the unnamed package, as
     * {@link #unnamed} finds it.
     */
    Optional<TypeHeader> find(String canonicalName) {
        return named(canonicalName).or(() -> unnamed(canonicalName));
    }

    /**
     * The header of the type of a named package whose canonical name is {@code canonicalName}, never a type of the
     * unnamed package. javac answers a name with a type whose canonical name it is, and that answer is taken when the
     * type belongs to a named package. Otherwise, when a top-level type of the unnamed package is named like the first
     * segment of the name, so that javac may have resolved the name through that type, the name is split at each of
     * its dots in turn, the last one first, into a package and the name of a type of it. The package is looked up
     * first, which makes javac enter its types, then a top-level type of that package named like the first segment of
     * the rest, in the module of that package, and then the member types it declares under each further segment.
     * Looking a package up makes javac list the names of its files, as it does for every package it reads a type of,
     * and read none of them.
     */
    Optional<TypeHeader> named(String canonicalName) {
        Optional<TypeHeader> cached = namedHeaders.get(canonicalName);
        if (cached == null) {
            cached = materialize(namedType(segments(canonicalName)));
            namedHeaders.put(canonicalName, cached);
        }
        return cached;
    }

    /**
     * The header of the type of the unnamed package whose canonical name is {@code canonicalName}: the top-level type
     * of that package named like its first segment, or the member type it declares under the second segment, and so
     * on to the last; never the synthetic class this task compiles.
     */
    Optional<TypeHeader> unnamed(String canonicalName) {
        Optional<TypeHeader> cached = unnamedHeaders.get(canonicalName);
        if (cached == null) {
            List<String> segments = segments(canonicalName);
            cached = materialize(segments.get(0).isEmpty() || segments.get(0).equals(PROBE_NAME) ? null
                    : members(topLevel(elements.getTypeElement(segments.get(0)), segments, 0), segments, 0));
            unnamedHeaders.put(canonicalName, cached);
        }
        return cached;
    }

    /**
     * The package that declares {@code header}, empty for the unnamed package. A classpath header spells every name
     * canonically, so its scope carries no package; access checks read the package here instead. For a header this
     * task materialized, it was read from the element the header was materialized from, never looked up by a name
     * that a type of the other package may share. A header this task did not materialize, such as one a caller built,
     * is looked up by its canonical name, as javac answers that name, empty when javac answers no type.
     */
    String packageOf(TypeHeader header) {
        String recorded = packages.get(header);
        if (recorded != null) {
            return recorded;
        }
        String canonicalName = header.canonicalName();
        TypeElement element = canonicalName.isEmpty() || canonicalName.equals(PROBE_NAME)
                ? null
                : elements.getTypeElement(canonicalName);
        return element == null ? "" : elements.getPackageOf(element).getQualifiedName().toString();
    }

    /**
     * Whether the class file of {@code header}, a header of the unnamed package this task materialized, gives
     * {@code canonicalName} to a type of a named package: in its supertypes, in the types of its fields and methods,
     * or as the type of an annotation on them. Code of the unnamed package reaches such a type through an import, in
     * which no class of the unnamed package is in scope (JLS 6.3), or as a member type it inherits from a type of a
     * named package. Either way its class file names it by its binary name, whose canonical name a member type of such
     * a class may share.
     */
    boolean namesNamedType(TypeHeader header, String canonicalName) {
        Set<String> named = namedReferences.get(header);
        return named != null && named.contains(canonicalName);
    }

    /**
     * Whether the class file of {@code header}, a header of the unnamed package this task materialized, gives
     * {@code canonicalName} both to a type of a named package and to a type of the unnamed package, which that name
     * alone does not tell apart.
     */
    boolean namesBothTypes(TypeHeader header, String canonicalName) {
        Set<String> unnamed = unnamedReferences.get(header);
        return namesNamedType(header, canonicalName) && unnamed != null && unnamed.contains(canonicalName);
    }

    @Override
    public void close() {
        try {
            fileManager.close();
        } catch (IOException failure) {
            throw new UncheckedIOException("cannot close the classpath headers", failure);
        }
    }

    /**
     * The header of {@code element}, empty for {@code null}, with the package that declares it recorded and, for a type
     * of the unnamed package, whether each type its header names belongs to a named package or to the unnamed package.
     * Those are the types that its supertypes, its fields, and the parameters and results of its methods name, with
     * the owners, type arguments, wildcard bounds and array components of those uses, and the types of the annotations
     * on the type, its fields and its methods.
     */
    private Optional<TypeHeader> materialize(TypeElement element) {
        if (element == null) {
            return Optional.empty();
        }
        Set<TypeElement> referenced = new HashSet<>();
        TypeHeader header = header(element, referenced);
        String packageName = elements.getPackageOf(element).getQualifiedName().toString();
        packages.put(header, packageName);
        if (packageName.isEmpty()) {
            Set<String> named = new HashSet<>();
            Set<String> unnamed = new HashSet<>();
            for (TypeElement type : referenced) {
                (elements.getPackageOf(type).isUnnamed() ? unnamed : named).add(type.getQualifiedName().toString());
            }
            namedReferences.put(header, named);
            unnamedReferences.put(header, unnamed);
        }
        return Optional.of(header);
    }

    /** The type of a named package that {@code segments} name, as {@link #named} looks it up, or {@code null}. */
    private TypeElement namedType(List<String> segments) {
        TypeElement answered = elements.getTypeElement(prefix(segments, segments.size()));
        if (answered != null && !elements.getPackageOf(answered).isUnnamed()) {
            return answered;
        }
        TypeElement obscuring = segments.get(0).isEmpty() ? null
                : topLevel(elements.getTypeElement(segments.get(0)), segments, 0);
        if (obscuring == null) {
            return null;
        }
        ModuleElement unnamed = elements.getModuleOf(obscuring);
        for (int top = segments.size() - 1; top > 0; top--) {
            PackageElement owner = unnamed == null ? elements.getPackageElement(prefix(segments, top))
                    : elements.getPackageElement(unnamed, prefix(segments, top));
            if (owner == null) {
                continue;
            }
            ModuleElement module = elements.getModuleOf(owner);
            String name = prefix(segments, top + 1);
            TypeElement found = members(topLevel(module == null ? elements.getTypeElement(name)
                    : elements.getTypeElement(module, name), segments, top), segments, top);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * {@code candidate} when it is the top-level type the segments up to {@code top} name: a type of the package the
     * segments before {@code top} name, the unnamed package for none, whose simple name is the one at {@code top};
     * {@code null} otherwise.
     */
    private static TypeElement topLevel(TypeElement candidate, List<String> segments, int top) {
        return candidate != null && candidate.getEnclosingElement() instanceof PackageElement owner
                && owner.getQualifiedName().contentEquals(prefix(segments, top))
                && candidate.getSimpleName().contentEquals(segments.get(top)) ? candidate : null;
    }

    /**
     * The member type that {@code type} declares under the segment after {@code top}, the member type that one
     * declares under the next segment, and so on to the last; {@code type} itself when {@code top} is the last
     * segment, and {@code null} when {@code type} is {@code null} or one of these types declares no member type of the
     * next segment's name. An inherited member type is not declared, so it is never found here.
     */
    private static TypeElement members(TypeElement type, List<String> segments, int top) {
        TypeElement found = type;
        for (int at = top + 1; found != null && at < segments.size(); at++) {
            TypeElement owner = found;
            found = null;
            for (TypeElement member : ElementFilter.typesIn(owner.getEnclosedElements())) {
                if (member.getSimpleName().contentEquals(segments.get(at))) {
                    found = member;
                    break;
                }
            }
        }
        return found;
    }

    /** The segments of {@code canonicalName}, the identifiers its dots separate. */
    private static List<String> segments(String canonicalName) {
        return List.of(canonicalName.split("\\.", -1));
    }

    /** The first {@code count} segments, joined by dots: a package or a type name, empty for none. */
    private static String prefix(List<String> segments, int count) {
        return String.join(".", segments.subList(0, count));
    }

    /** The header of {@code element}; every type its header names is added to {@code referenced}. */
    private static TypeHeader header(TypeElement element, Set<TypeElement> referenced) {
        List<String> typeParameters = typeParameterNames(element.getTypeParameters());
        List<TypeUse> supertypes = new ArrayList<>();
        if (element.getSuperclass().getKind() == TypeKind.DECLARED) {
            supertypes.add(MirrorTypeUses.use(element.getSuperclass(), referenced));
        }
        for (TypeMirror implemented : element.getInterfaces()) {
            supertypes.add(MirrorTypeUses.use(implemented, referenced));
        }
        List<TypeHeader.Member> members = new ArrayList<>();
        List<String> constants = new ArrayList<>();
        for (Element enclosed : element.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.ENUM_CONSTANT) {
                constants.add(enclosed.getSimpleName().toString());
            } else if (enclosed.getKind() == ElementKind.FIELD) {
                members.add(field((VariableElement) enclosed, referenced));
            } else if (enclosed.getKind() == ElementKind.METHOD) {
                members.add(method((ExecutableElement) enclosed, referenced));
            }
        }
        return new TypeHeader(element.getQualifiedName().toString(), element.getKind(),
                element.getModifiers(), typeParameters, supertypes, members, markers(element, referenced), List.of(),
                constants, TypeHeader.Scope.BINARY, Optional.empty());
    }

    private static TypeHeader.Member method(ExecutableElement method, Set<TypeElement> referenced) {
        List<String> typeParameters = typeParameterNames(method.getTypeParameters());
        List<TypeUse> parameterTypes = new ArrayList<>();
        for (VariableElement parameter : method.getParameters()) {
            parameterTypes.add(MirrorTypeUses.use(parameter.asType(), referenced));
        }
        return new TypeHeader.Member(method.getSimpleName().toString(), ElementKind.METHOD,
                method.getModifiers(), typeParameters, parameterTypes,
                MirrorTypeUses.use(method.getReturnType(), referenced), markers(method, referenced), Optional.empty());
    }

    /** The simple names of the type parameters a type or a method declares, in declaration order. */
    private static List<String> typeParameterNames(List<? extends TypeParameterElement> parameters) {
        List<String> names = new ArrayList<>();
        for (TypeParameterElement parameter : parameters) {
            names.add(parameter.getSimpleName().toString());
        }
        return names;
    }

    private static TypeHeader.Member field(VariableElement field, Set<TypeElement> referenced) {
        return new TypeHeader.Member(field.getSimpleName().toString(), ElementKind.FIELD,
                field.getModifiers(), List.of(), List.of(), MirrorTypeUses.use(field.asType(), referenced),
                markers(field, referenced), Optional.empty());
    }

    private static List<TypeHeader.Marker> markers(Element element, Set<TypeElement> referenced) {
        List<TypeHeader.Marker> markers = new ArrayList<>();
        for (AnnotationMirror annotation : element.getAnnotationMirrors()) {
            List<String> classLiterals = new ArrayList<>();
            for (AnnotationValue value : annotation.getElementValues().values()) {
                classLiterals(value, classLiterals);
            }
            TypeElement annotationType = (TypeElement) annotation.getAnnotationType().asElement();
            referenced.add(annotationType);
            markers.add(new TypeHeader.Marker(annotationType.getQualifiedName().toString(), classLiterals,
                    Optional.empty()));
        }
        return markers;
    }

    private static void classLiterals(AnnotationValue value, List<String> classLiterals) {
        Object raw = value.getValue();
        if (raw instanceof TypeMirror mirror) {
            classLiterals.add(MirrorTypeUses.spell(mirror));
        } else if (raw instanceof List<?> elements) {
            for (Object element : elements) {
                if (element instanceof AnnotationValue nested) {
                    classLiterals(nested, classLiterals);
                }
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
