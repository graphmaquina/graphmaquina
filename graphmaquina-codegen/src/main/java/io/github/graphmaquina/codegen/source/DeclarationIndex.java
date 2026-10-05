package io.github.graphmaquina.codegen.source;

import io.github.graphmaquina.codegen.CodegenException;
import io.github.graphmaquina.codegen.GenerationRequest;
import io.github.graphmaquina.codegen.model.SourceLocation;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;

/**
 * The headers the generator can see: every type declared by the original sources plus every type the
 * classpath declares, under one name resolution.
 *
 * <p>Resolution is lazy on purpose. Import syntax is recorded without validating its targets, so a source may
 * import or annotate itself with a type this generator has not produced yet. A name is only resolved when the
 * header model actually needs it, and only then can it fail with {@code SG601}.</p>
 */
public final class DeclarationIndex implements AutoCloseable {

    private static final String UNRESOLVED = "SG601";
    private static final String COLLISION = "SG603";

    private final Map<String, TypeHeader> sources;
    private final ClasspathDeclarations classpath;

    private DeclarationIndex(Map<String, TypeHeader> sources, ClasspathDeclarations classpath) {
        this.sources = sources;
        this.classpath = classpath;
    }

    /**
     * Reads every declaration of {@code units} and opens the classpath headers they resolve against, at release
     * {@value GenerationRequest#DEFAULT_RELEASE}.
     */
    public static DeclarationIndex open(List<SourceUnit> units, List<Path> classpath) {
        return open(units, classpath, GenerationRequest.DEFAULT_RELEASE, GenerationRequest.DEFAULT_ENCODING);
    }

    /**
     * Reads every declaration of {@code units} and opens the classpath headers they resolve against, at the
     * {@code release} and in the {@code encoding} of the compilation the units belong to.
     */
    static DeclarationIndex open(List<SourceUnit> units, List<Path> classpath, int release, Charset encoding) {
        Objects.requireNonNull(units, "units");
        Objects.requireNonNull(classpath, "classpath");
        ClasspathDeclarations declarations = ClasspathDeclarations.open(classpath, release, encoding);
        try {
            Map<String, TypeHeader> sources = new LinkedHashMap<>();
            for (SourceUnit unit : units) {
                for (TypeHeader header : SourceDeclarations.declarations(unit,
                        declarations.sourcePositions())) {
                    sources.putIfAbsent(header.canonicalName(), header);
                }
            }
            return new DeclarationIndex(sources, declarations);
        } catch (RuntimeException failure) {
            declarations.close();
            throw failure;
        }
    }

    /**
     * Every header read from the original sources, in the order the units declared them; of two declarations of
     * one canonical name, only the one read first is kept.
     */
    public List<TypeHeader> sourceHeaders() {
        return List.copyOf(sources.values());
    }

    /** The header declared under exactly {@code canonicalName}, sources first, then the classpath. */
    public Optional<TypeHeader> find(String canonicalName) {
        Objects.requireNonNull(canonicalName, "canonicalName");
        TypeHeader source = sources.get(canonicalName);
        return source != null ? Optional.of(source) : classpath.find(canonicalName);
    }

    /**
     * Resolves {@code spelledName} as {@code context} spells it in its body: enclosing and inherited member types
     * first, then single imports, the declaring package, on-demand imports and finally {@code java.lang}. An
     * import only offers the types it can access, and a name that two imports offer at once is reported as
     * {@code SG601} instead of being chosen by order.
     */
    public Optional<TypeHeader> resolve(TypeHeader context, String spelledName) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(spelledName, "spelledName");
        return lookup(context, spelledName, false, new HashSet<>());
    }

    /**
     * Resolves {@code spelledName} as the header of {@code declaration} spells it: in its own annotations and in
     * its extends and implements clauses. javac resolves those in the scope that encloses the declaration, where
     * the member types the declaration declares or inherits are not visible (JLS 6.3), so only its enclosing
     * declarations contribute member types. Its own type parameters were already bound when it was read.
     */
    Optional<TypeHeader> resolveInHeader(TypeHeader declaration, String spelledName) {
        Objects.requireNonNull(declaration, "declaration");
        Objects.requireNonNull(spelledName, "spelledName");
        return lookup(declaration, spelledName, true, new HashSet<>());
    }

    /**
     * Replaces every spelled name in {@code use} by its canonical name, leaving unresolvable names alone. The
     * parameterized owner of an inner type is found by walking supertypes and enclosing declarations. In a
     * hierarchy javac rejects as cyclic, such as a class that extends a member class of its own, that walk comes
     * back to an owner an enclosing call is still computing; that owner is taken raw there, so the run ends and
     * javac gets to report the cycle.
     */
    public TypeUse canonicalize(TypeHeader context, TypeUse use) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(use, "use");
        return canonicalize(context, use, false, new HashSet<>());
    }

    /** {@link #canonicalize} for a use the header of {@code declaration} spells, read as {@link #resolveInHeader}. */
    TypeUse canonicalizeInHeader(TypeHeader declaration, TypeUse use) {
        Objects.requireNonNull(declaration, "declaration");
        Objects.requireNonNull(use, "use");
        return canonicalize(declaration, use, true, new HashSet<>());
    }

    private TypeUse canonicalize(TypeHeader context, TypeUse use, boolean header, Set<String> pendingOwners) {
        return switch (use) {
            case TypeUse.Declared declared -> {
                List<TypeUse> arguments = new ArrayList<>();
                for (TypeUse argument : declared.arguments()) {
                    arguments.add(canonicalize(context, argument, header, pendingOwners));
                }
                Optional<TypeHeader> resolved = lookup(context, declared.name(), header, new HashSet<>());
                Optional<TypeUse> owner =
                        declared.owner().map(value -> canonicalize(context, value, header, pendingOwners));
                if (resolved.isPresent() && resolved.get().kind() == ElementKind.CLASS
                        && !resolved.get().modifiers().contains(Modifier.STATIC)) {
                    int separator = declared.name().lastIndexOf('.');
                    if (owner.isEmpty()) {
                        owner = separator < 0 ? implicitOwner(context, resolved.get(), header, pendingOwners)
                                : lookup(context, declared.name().substring(0, separator), header, new HashSet<>())
                                    .map(enclosing -> TypeUse.Declared.of(enclosing.canonicalName()));
                    }
                    String parent = parentName(resolved.get());
                    if (owner.isPresent() && owner.get() instanceof TypeUse.Declared selected) {
                        owner = asSuper(selected, parent, new HashSet<>(), pendingOwners)
                                .map(value -> (TypeUse) value);
                    }
                }
                yield new TypeUse.Declared(resolved.map(TypeHeader::canonicalName).orElse(declared.name()),
                        arguments, owner);
            }
            case TypeUse.Array array ->
                    new TypeUse.Array(canonicalize(context, array.component(), header, pendingOwners));
            case TypeUse.Wildcard wildcard -> new TypeUse.Wildcard(
                    wildcard.upperBound().map(bound -> canonicalize(context, bound, header, pendingOwners)),
                    wildcard.lowerBound().map(bound -> canonicalize(context, bound, header, pendingOwners)));
            case TypeUse.Variable variable -> variable;
            case TypeUse.Primitive primitive -> primitive;
        };
    }

    /**
     * An unqualified inner type in its enclosing generic declaration implicitly captures that owner's variables.
     * A header use is seen from the declaration that encloses its own, as javac sees it. A request for an owner
     * that an enclosing call is still computing gets the raw owner instead of recursing without end. An inheritance
     * cycle through an enclosing class, which javac rejects, leads back to one.
     */
    private Optional<TypeUse> implicitOwner(TypeHeader context, TypeHeader member, boolean header,
            Set<String> pendingOwners) {
        if (member.kind() != ElementKind.CLASS || member.modifiers().contains(Modifier.STATIC)) {
            return Optional.empty();
        }
        String parent = parentName(member);
        if (find(parent).isEmpty()) {
            return Optional.empty();
        }
        String token = (header ? "header:" : "body:") + context.canonicalName() + '#' + member.canonicalName();
        if (!pendingOwners.add(token)) {
            return Optional.of(TypeUse.Declared.of(parent));
        }
        try {
            for (TypeHeader scope : scopes(context, header)) {
                if (!scope.canonicalName().equals(member.canonicalName())) {
                    Optional<TypeUse.Declared> owner = asSuper(lexicalType(scope, pendingOwners), parent,
                            new HashSet<>(), pendingOwners);
                    if (owner.isPresent()) {
                        return owner.map(value -> (TypeUse) value);
                    }
                }
                if (scope.modifiers().contains(Modifier.STATIC)) {
                    break;
                }
            }
            return Optional.empty();
        } finally {
            pendingOwners.remove(token);
        }
    }

    private TypeUse.Declared lexicalType(TypeHeader header, Set<String> pendingOwners) {
        List<TypeUse> variables = new ArrayList<>();
        for (int at = 0; at < header.typeParameters().size(); at++) {
            variables.add(new TypeUse.Variable(header.typeParameters().get(at), header.parameterKey(at)));
        }
        return new TypeUse.Declared(header.canonicalName(), variables,
                implicitOwner(header, header, false, pendingOwners));
    }

    private static String parentName(TypeHeader header) {
        int separator = header.canonicalName().lastIndexOf('.');
        return separator < 0 ? "" : header.canonicalName().substring(0, separator);
    }

    /** Specializes an inherited inner type's actual declaring owner, including reordered type arguments. */
    private Optional<TypeUse.Declared> asSuper(TypeUse.Declared use, String target, Set<String> visited,
            Set<String> pendingOwners) {
        if (use.name().equals(target)) {
            return Optional.of(use);
        }
        Optional<TypeHeader> found = find(use.name());
        if (found.isEmpty() || !visited.add(use.name())) {
            return Optional.empty();
        }
        TypeHeader header = found.get();
        Map<TypeParameterKey, TypeUse> environment = new LinkedHashMap<>();
        bindOwner(use, environment);
        for (TypeUse supertype : header.supertypes()) {
            TypeUse specialized = substitute(canonicalize(header, supertype, true, pendingOwners), environment);
            if (specialized instanceof TypeUse.Declared declared) {
                if (!header.typeParameters().isEmpty() && use.arguments().isEmpty()) {
                    declared = new TypeUse.Declared(declared.name(), List.of(), Optional.empty());
                }
                Optional<TypeUse.Declared> result = asSuper(declared, target, visited, pendingOwners);
                if (result.isPresent()) {
                    return result;
                }
            }
        }
        return Optional.empty();
    }

    private void bindOwner(TypeUse.Declared use, Map<TypeParameterKey, TypeUse> environment) {
        use.owner().filter(TypeUse.Declared.class::isInstance).map(TypeUse.Declared.class::cast)
                .ifPresent(owner -> bindOwner(owner, environment));
        find(use.name()).ifPresent(header -> {
            for (int at = 0; at < Math.min(header.typeParameters().size(), use.arguments().size()); at++) {
                environment.put(header.parameterKey(at), use.arguments().get(at));
            }
        });
    }

    /** Applies {@code environment} to every type variable of {@code use}, owners and arguments included. */
    public TypeUse substitute(TypeUse use, Map<TypeParameterKey, TypeUse> environment) {
        Objects.requireNonNull(use, "use");
        Objects.requireNonNull(environment, "environment");
        return switch (use) {
            case TypeUse.Variable variable -> environment.getOrDefault(variable.key(), variable);
            case TypeUse.Declared declared -> {
                List<TypeUse> arguments = new ArrayList<>();
                for (TypeUse argument : declared.arguments()) {
                    arguments.add(substitute(argument, environment));
                }
                yield new TypeUse.Declared(declared.name(), arguments,
                        declared.owner().map(owner -> substitute(owner, environment)));
            }
            case TypeUse.Array array -> new TypeUse.Array(substitute(array.component(), environment));
            case TypeUse.Wildcard wildcard -> new TypeUse.Wildcard(
                    wildcard.upperBound().map(bound -> substitute(bound, environment)),
                    wildcard.lowerBound().map(bound -> substitute(bound, environment)));
            case TypeUse.Primitive primitive -> primitive;
        };
    }

    /**
     * Whether {@code marker}, written in the body of {@code context} on one of its members, names
     * {@code annotationType}. An annotation whose last segment cannot match is never resolved, so a marker the
     * generator is still about to produce costs nothing here.
     *
     * @throws CodegenException {@code SG601} when a marker spelled like {@code annotationType} resolves to nothing
     *     and {@code annotationType} itself is not on the classpath either, which means the generator cannot
     *     recognize any marker: graphmaquina-core is missing from the classpath of the request
     */
    public boolean marks(TypeHeader context, TypeHeader.Marker marker, String annotationType) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(marker, "marker");
        Objects.requireNonNull(annotationType, "annotationType");
        return marks(context, marker, annotationType, false);
    }

    /**
     * {@link #marks} for a marker written on {@code declaration} itself, which javac resolves as
     * {@link #resolveInHeader} does: a member type of the declaration named like the marker never hides it.
     */
    boolean marksInHeader(TypeHeader declaration, TypeHeader.Marker marker, String annotationType) {
        Objects.requireNonNull(declaration, "declaration");
        Objects.requireNonNull(marker, "marker");
        Objects.requireNonNull(annotationType, "annotationType");
        return marks(declaration, marker, annotationType, true);
    }

    private boolean marks(TypeHeader context, TypeHeader.Marker marker, String annotationType, boolean header) {
        String simpleName = annotationType.substring(annotationType.lastIndexOf('.') + 1);
        if (!marker.name().equals(simpleName) && !marker.name().endsWith('.' + simpleName)) {
            return false;
        }
        Optional<TypeHeader> resolved = lookup(context, marker.name(), header, new HashSet<>());
        if (resolved.isEmpty() && find(annotationType).isEmpty()) {
            String message = "the marker @" + marker.name() + " in " + context.canonicalName()
                    + " cannot be recognized: " + annotationType + " is not on the generator classpath";
            throw marker.origin().or(context::origin)
                    .map(origin -> new CodegenException(UNRESOLVED, message, origin))
                    .orElseGet(() -> new CodegenException(UNRESOLVED, message));
        }
        return resolved.map(type -> type.canonicalName().equals(annotationType)).orElse(Boolean.FALSE);
    }

    @Override
    public void close() {
        classpath.close();
    }

    /** Resolves {@code spelledName} in the body of {@code context}, or in its header when {@code header} is set. */
    private Optional<TypeHeader> lookup(TypeHeader context, String spelledName, boolean header,
            Set<String> pending) {
        List<String> segments = List.of(spelledName.split("\\.", -1));
        if (segments.size() == 1) {
            return simple(context, spelledName, header, pending);
        }
        Optional<TypeHeader> head = simple(context, segments.get(0), header, pending);
        if (head.isPresent()) {
            Optional<TypeHeader> walked = walk(head.get(), segments.subList(1, segments.size()), pending);
            if (walked.isPresent()) {
                return walked;
            }
        }
        return qualified(segments, pending);
    }

    private Optional<TypeHeader> simple(TypeHeader context, String name, boolean header, Set<String> pending) {
        String token = (header ? "header:" : "simple:") + context.canonicalName() + '#' + name;
        if (!pending.add(token)) {
            return Optional.empty();
        }
        try {
            for (TypeHeader enclosing : scopes(context, header)) {
                Optional<TypeHeader> member = memberType(enclosing, name, pending);
                if (member.isPresent()) {
                    return member;
                }
            }
            TypeHeader.Scope scope = context.scope();
            Optional<TypeHeader> imported = unique(scope.singleImports().stream()
                    .filter(single -> single.endsWith('.' + name))
                    .map(single -> qualified(List.of(single.split("\\.", -1)), pending)
                            .filter(type -> importable(type, scope.packageName())))
                    .toList(), name, context);
            if (imported.isPresent()) {
                return imported;
            }
            // In the unnamed package, a sibling's canonical name is its simple name.
            Optional<TypeHeader> sibling =
                    find(scope.packageName().isEmpty() ? name : scope.packageName() + '.' + name);
            if (sibling.isPresent()) {
                return sibling;
            }
            Optional<TypeHeader> onDemand = unique(scope.onDemandImports().stream()
                    .map(prefix -> qualified(List.of((prefix + '.' + name).split("\\.", -1)), pending)
                            .filter(type -> importable(type, scope.packageName())))
                    .toList(), name, context);
            if (onDemand.isPresent()) {
                return onDemand;
            }
            return find("java.lang." + name).filter(type -> importable(type, scope.packageName()));
        } finally {
            pending.remove(token);
        }
    }

    /**
     * Whether an import into a compilation unit of {@code packageName} imports {@code type} at all: an import
     * only offers accessible types (JLS 7.5), so a private type never, and one that is not public only to its
     * own package. The implicit on-demand import of {@code java.lang} follows the same rule.
     */
    private boolean importable(TypeHeader type, String packageName) {
        if (type.modifiers().contains(Modifier.PUBLIC)) {
            return true;
        }
        return !type.modifiers().contains(Modifier.PRIVATE) && packageOf(type).equals(packageName);
    }

    /**
     * Whether {@code member}, a member type of a supertype of {@code owner}, is a member of {@code owner} too: a
     * private member type is never inherited, and one with package access only inside its own package (JLS 8.2).
     */
    private boolean inheritedBy(TypeHeader member, TypeHeader owner) {
        Set<Modifier> modifiers = member.modifiers();
        if (modifiers.contains(Modifier.PRIVATE)) {
            return false;
        }
        if (modifiers.contains(Modifier.PUBLIC) || modifiers.contains(Modifier.PROTECTED)) {
            return true;
        }
        return packageOf(member).equals(packageOf(owner));
    }

    /**
     * Why a top-level type generated in {@code packageName} cannot name {@code type}, or empty when it can. No
     * declaration from {@code type} outwards may be private, and each one that is not public must belong to
     * {@code packageName} (JLS 6.6.1); the reason names the first declaration that fails.
     */
    Optional<String> inaccessibility(TypeHeader type, String packageName) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(packageName, "packageName");
        for (Optional<TypeHeader> current = Optional.of(type); current.isPresent();
                current = enclosingDeclaration(current.get())) {
            TypeHeader declaration = current.get();
            if (declaration.modifiers().contains(Modifier.PRIVATE)) {
                return Optional.of(declaration.canonicalName() + " is private");
            }
            String declaringPackage = packageOf(declaration);
            if (!declaration.modifiers().contains(Modifier.PUBLIC) && !declaringPackage.equals(packageName)) {
                return Optional.of(declaration.canonicalName() + " is not public and belongs to "
                        + (declaringPackage.isEmpty() ? "the unnamed package" : "package " + declaringPackage));
            }
        }
        return Optional.empty();
    }

    /**
     * Refuses the qualified names {@code unit}, a top-level type generated in {@code packageName}, must write when a
     * type the application or the JDK puts in scope there is named like the first segment of one of them: a type
     * that package declares, or a public type of {@code java.lang}, which every compilation unit imports on demand.
     * Inside the unit that type obscures the package of the same name (JLS 6.4.2), so the qualified name would mean
     * a member of the type instead. The types this generator creates in the package are checked by the emitters,
     * which know them.
     *
     * @param names qualified names that each start with a package
     * @throws CodegenException {@code SG603} naming the type and {@code unit}, anchored at {@code origin}
     */
    void requireUnobscured(String packageName, String unit, List<String> names, SourceLocation origin) {
        Objects.requireNonNull(packageName, "packageName");
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(names, "names");
        Objects.requireNonNull(origin, "origin");
        for (String name : names) {
            String segment = name.substring(0, name.indexOf('.'));
            Optional<TypeHeader> obscurer = find(packageName.isEmpty() ? segment : packageName + '.' + segment)
                    .filter(type -> packageOf(type).equals(packageName))
                    .or(() -> find("java.lang." + segment).filter(type -> importable(type, packageName)));
            if (obscurer.isPresent()) {
                throw new CodegenException(COLLISION, unit + " cannot name " + name + ": the type "
                        + obscurer.get().canonicalName() + " obscures the package " + segment + " there", origin);
            }
        }
    }

    /** The declaration that directly encloses {@code header}, empty for a top-level type. */
    private Optional<TypeHeader> enclosingDeclaration(TypeHeader header) {
        int separator = header.canonicalName().lastIndexOf('.');
        return separator > packageOf(header).length()
                ? find(header.canonicalName().substring(0, separator))
                : Optional.empty();
    }

    /**
     * The package that declares {@code header}, empty for the unnamed package: as its source spells it, or as the
     * classpath records it.
     */
    String packageOf(TypeHeader header) {
        return header.origin().isPresent()
                ? header.scope().packageName()
                : classpath.packageOf(header.canonicalName());
    }

    /** Rejects a simple name that several imports offer at once instead of choosing one by order. */
    private Optional<TypeHeader> unique(List<Optional<TypeHeader>> candidates, String name,
            TypeHeader context) {
        Set<String> found = new LinkedHashSet<>();
        TypeHeader first = null;
        for (Optional<TypeHeader> candidate : candidates) {
            if (candidate.isPresent()) {
                found.add(candidate.get().canonicalName());
                if (first == null) {
                    first = candidate.get();
                }
            }
        }
        if (found.size() > 1) {
            String message = "the type " + name + " is ambiguous between " + String.join(" and ", found);
            throw context.origin()
                    .map(origin -> new CodegenException(UNRESOLVED, message, origin))
                    .orElseGet(() -> new CodegenException(UNRESOLVED, message));
        }
        return Optional.ofNullable(first);
    }

    private Optional<TypeHeader> qualified(List<String> segments, Set<String> pending) {
        for (int length = segments.size(); length >= 1; length--) {
            Optional<TypeHeader> owner = find(String.join(".", segments.subList(0, length)));
            if (owner.isEmpty()) {
                continue;
            }
            Optional<TypeHeader> walked =
                    walk(owner.get(), segments.subList(length, segments.size()), pending);
            if (walked.isPresent()) {
                return walked;
            }
        }
        return Optional.empty();
    }

    private Optional<TypeHeader> walk(TypeHeader owner, List<String> segments, Set<String> pending) {
        TypeHeader current = owner;
        for (String segment : segments) {
            Optional<TypeHeader> next = memberType(current, segment, pending);
            if (next.isEmpty()) {
                return Optional.empty();
            }
            current = next.get();
        }
        return Optional.of(current);
    }

    /**
     * A member type {@code owner} declares or inherits from one of its supertypes, which are resolved as the
     * header of {@code owner} spells them; a private member type of a supertype, or one with package access
     * declared in another package, is not inherited. A dotted spelling is never assumed to be a canonical name:
     * the declaring header found here is the one whose canonical name is recorded.
     */
    private Optional<TypeHeader> memberType(TypeHeader owner, String simpleName, Set<String> pending) {
        String token = "member:" + owner.canonicalName() + '.' + simpleName;
        if (!pending.add(token)) {
            return Optional.empty();
        }
        try {
            Optional<TypeHeader> declared = find(owner.canonicalName() + '.' + simpleName);
            if (declared.isPresent()) {
                return declared;
            }
            for (TypeUse supertype : owner.supertypes()) {
                if (!(supertype instanceof TypeUse.Declared use)) {
                    continue;
                }
                Optional<TypeHeader> superHeader = lookup(owner, use.name(), true, pending);
                if (superHeader.isEmpty()) {
                    continue;
                }
                Optional<TypeHeader> inherited = memberType(superHeader.get(), simpleName, pending)
                        .filter(member -> inheritedBy(member, owner));
                if (inherited.isPresent()) {
                    return inherited;
                }
            }
            return Optional.empty();
        } finally {
            pending.remove(token);
        }
    }

    /**
     * The declarations whose member types a name spelled by {@code context} can see, innermost first: its whole
     * enclosing chain for a name its body spells, and only the declarations enclosing it for one its header spells.
     */
    private List<TypeHeader> scopes(TypeHeader context, boolean header) {
        List<TypeHeader> chain = enclosingChain(context);
        return header ? chain.subList(1, chain.size()) : chain;
    }

    /** The declaration itself and the declarations that lexically enclose it, innermost first. */
    private List<TypeHeader> enclosingChain(TypeHeader context) {
        List<TypeHeader> chain = new ArrayList<>();
        chain.add(context);
        String enclosing = context.canonicalName();
        while (enclosing.lastIndexOf('.') > 0) {
            enclosing = enclosing.substring(0, enclosing.lastIndexOf('.'));
            TypeHeader header = sources.get(enclosing);
            if (header == null) {
                break;
            }
            chain.add(header);
        }
        return chain;
    }
}
