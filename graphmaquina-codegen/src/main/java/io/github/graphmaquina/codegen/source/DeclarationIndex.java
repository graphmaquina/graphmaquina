package io.github.graphmaquina.codegen.source;

import io.github.graphmaquina.codegen.CodegenException;
import io.github.graphmaquina.codegen.GenerationRequest;
import io.github.graphmaquina.codegen.model.SourceLocation;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import lombok.NonNull;

/**
 * The headers the generator can see: every type declared by the original sources plus every type the
 * classpath declares, under one name resolution.
 *
 * <p>Resolution is lazy on purpose. Import syntax is recorded without validating its targets, so a source may
 * import or annotate itself with a type this generator has not produced yet. A name is only resolved when the
 * header model actually needs it, and only then can it fail with {@code SG601}.</p>
 *
 * <p>One canonical name can name two types: a type of a named package, such as {@code shop.Model.Order} of the
 * package {@code shop}, and a member type of a class of the unnamed package named like the first segment of that
 * package, such as the member {@code Model.Order} of a class {@code shop}. javac admits both. Code of a named package
 * never sees a type of the unnamed package. In code of the unnamed package, such a class obscures the package of its
 * name (JLS 6.4.2) in a name that starts with it, but no class of the unnamed package is in scope in an import
 * (JLS 6.3). So the index keeps the declarations of the two packages apart. It never answers a type of the unnamed
 * package for a name that code or a binary of a named package spells, that an import names, that starts with a
 * package, or that the class file of a binary of the unnamed package gives a type of a named package. It finds a
 * member type and an enclosing declaration in the package of the type they belong to, and it carries a type it has
 * resolved on as that header rather than looking its canonical name up again.</p>
 */
public final class DeclarationIndex implements AutoCloseable {

    private static final String UNRESOLVED = "SG601";
    private static final String COLLISION = "SG603";

    /** The source declarations of named packages, by canonical name. */
    private final Map<String, TypeHeader> sources;
    /** The source declarations of the unnamed package, by canonical name. */
    private final Map<String, TypeHeader> unnamedSources;
    /** Every source declaration either map keeps, in the order the units declared them. */
    private final List<TypeHeader> sourceHeaders;
    private final ClasspathDeclarations classpath;

    private DeclarationIndex(Map<String, TypeHeader> sources, Map<String, TypeHeader> unnamedSources,
            List<TypeHeader> sourceHeaders, ClasspathDeclarations classpath) {
        this.sources = sources;
        this.unnamedSources = unnamedSources;
        this.sourceHeaders = sourceHeaders;
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
    static DeclarationIndex open(@NonNull List<SourceUnit> units, @NonNull List<Path> classpath, int release,
            Charset encoding) {
        ClasspathDeclarations declarations = ClasspathDeclarations.open(classpath, release, encoding);
        try {
            Map<String, TypeHeader> sources = new HashMap<>();
            Map<String, TypeHeader> unnamedSources = new HashMap<>();
            List<TypeHeader> sourceHeaders = new ArrayList<>();
            for (SourceUnit unit : units) {
                for (TypeHeader header : SourceDeclarations.declarations(unit,
                        declarations.sourcePositions())) {
                    Map<String, TypeHeader> side = header.scope().packageName().isEmpty() ? unnamedSources : sources;
                    if (side.putIfAbsent(header.canonicalName(), header) == null) {
                        sourceHeaders.add(header);
                    }
                }
            }
            return new DeclarationIndex(sources, unnamedSources, List.copyOf(sourceHeaders), declarations);
        } catch (RuntimeException failure) {
            declarations.close();
            throw failure;
        }
    }

    /**
     * Every header read from the original sources, in the order the units declared them. Of two declarations of one
     * canonical name in named packages, or of two in the unnamed package, only the one read first is kept; a
     * declaration of a named package and one of the unnamed package are two types, and both are kept.
     */
    public List<TypeHeader> sourceHeaders() {
        return sourceHeaders;
    }

    /**
     * The header declared under exactly {@code canonicalName}: a type of a named package when one has that name,
     * which is what the name means in an import (JLS 6.3), in code of a named package, and wherever its first segment
     * names a package, and otherwise a type of the unnamed package. Either is a source when one declares it, or else
     * the classpath's.
     */
    public Optional<TypeHeader> find(@NonNull String canonicalName) {
        return declared(canonicalName, false).or(() -> declared(canonicalName, true));
    }

    /**
     * The type of the unnamed package, when {@code unnamed} is set, or else of a named package, whose canonical name is
     * {@code canonicalName}: the source declaration of that name when there is one, and otherwise the classpath's.
     */
    private Optional<TypeHeader> declared(String canonicalName, boolean unnamed) {
        TypeHeader source = (unnamed ? unnamedSources : sources).get(canonicalName);
        if (source != null) {
            return Optional.of(source);
        }
        return unnamed ? classpath.unnamed(canonicalName) : classpath.named(canonicalName);
    }

    /**
     * Resolves {@code spelledName} as {@code context} spells it in its body: enclosing and inherited member types
     * first, then single imports, the declaring package, on-demand imports and finally {@code java.lang}. An
     * import only offers the types it can access, and a name that two imports offer at once is reported as
     * {@code SG601} instead of being chosen by order.
     */
    public Optional<TypeHeader> resolve(@NonNull TypeHeader context, @NonNull String spelledName) {
        return lookup(context, spelledName, false, new HashSet<>());
    }

    /**
     * Resolves {@code spelledName} as the header of {@code declaration} spells it: in its own annotations and in
     * its extends and implements clauses. javac resolves those in the scope that encloses the declaration, where
     * the member types the declaration declares or inherits are not visible (JLS 6.3), so only its enclosing
     * declarations contribute member types. Its own type parameters were already bound when it was read.
     */
    Optional<TypeHeader> resolveInHeader(@NonNull TypeHeader declaration, @NonNull String spelledName) {
        return lookup(declaration, spelledName, true, new HashSet<>());
    }

    /**
     * Replaces every spelled name in {@code use} by its canonical name, leaving unresolvable names alone. The
     * parameterized owner of an inner type is found by walking supertypes and enclosing declarations. In a
     * hierarchy javac rejects as cyclic, such as a class that extends a member class of its own, that walk comes
     * back to an owner an enclosing call is still computing; that owner is taken raw there, so the run ends and
     * javac gets to report the cycle.
     */
    public TypeUse canonicalize(@NonNull TypeHeader context, @NonNull TypeUse use) {
        return canonicalize(context, use, false, new HashSet<>());
    }

    /** {@link #canonicalize} for a use the header of {@code declaration} spells, read as {@link #resolveInHeader}. */
    TypeUse canonicalizeInHeader(@NonNull TypeHeader declaration, @NonNull TypeUse use) {
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
                        declared.owner().map((TypeUse value) -> canonicalize(context, value, header, pendingOwners));
                if (resolved.isPresent() && resolved.get().kind() == ElementKind.CLASS
                        && !resolved.get().modifiers().contains(Modifier.STATIC)) {
                    int separator = declared.name().lastIndexOf('.');
                    // The type the owner names, as the code spelling it resolves it; none for an implicit owner,
                    // which already names the declaration enclosing the inner type.
                    Optional<TypeHeader> ownerType = declared.owner()
                            .filter(TypeUse.Declared.class::isInstance).map(TypeUse.Declared.class::cast)
                            .flatMap((TypeUse.Declared spelled) ->
                                    lookup(context, spelled.name(), header, new HashSet<>()));
                    if (owner.isEmpty()) {
                        if (separator < 0) {
                            owner = implicitOwner(context, resolved.get(), header, pendingOwners);
                        } else {
                            ownerType = lookup(context, declared.name().substring(0, separator), header,
                                    new HashSet<>());
                            owner = ownerType.map(
                                    (TypeHeader enclosing) -> TypeUse.Declared.of(enclosing.canonicalName()));
                        }
                    }
                    Optional<TypeHeader> parent = enclosingDeclaration(resolved.get());
                    if (parent.isEmpty()) {
                        owner = Optional.empty();
                    } else if (owner.isPresent() && owner.get() instanceof TypeUse.Declared selected) {
                        owner = asSuper(selected, ownerType, parent.get(), identities(), pendingOwners)
                                .map((TypeUse.Declared value) -> (TypeUse) value);
                    }
                }
                yield new TypeUse.Declared(resolved.map(TypeHeader::canonicalName).orElse(declared.name()),
                        arguments, owner);
            }
            case TypeUse.Array array ->
                    new TypeUse.Array(canonicalize(context, array.component(), header, pendingOwners));
            case TypeUse.Wildcard wildcard -> new TypeUse.Wildcard(
                    wildcard.upperBound().map((TypeUse bound) -> canonicalize(context, bound, header, pendingOwners)),
                    wildcard.lowerBound().map((TypeUse bound) -> canonicalize(context, bound, header, pendingOwners)));
            case TypeUse.Variable variable -> variable;
            case TypeUse.Primitive primitive -> primitive;
        };
    }

    /**
     * An unqualified inner type in its enclosing generic declaration implicitly captures that owner's variables.
     * A header use is seen from the declaration that encloses its own, as javac sees it. A request for an owner
     * that an enclosing call is still computing gets the raw owner instead of recursing without end. An inheritance
     * cycle through an enclosing class, which javac rejects, leads back to one. The owner is the declaration that
     * encloses {@code member}, looked up in the package of {@code member}.
     */
    private Optional<TypeUse> implicitOwner(TypeHeader context, TypeHeader member, boolean header,
            Set<String> pendingOwners) {
        if (member.kind() != ElementKind.CLASS || member.modifiers().contains(Modifier.STATIC)) {
            return Optional.empty();
        }
        Optional<TypeHeader> parent = enclosingDeclaration(member);
        if (parent.isEmpty()) {
            return Optional.empty();
        }
        String token = (header ? "header:" : "body:") + side(context) + context.canonicalName() + '#' + side(member)
                + member.canonicalName();
        if (!pendingOwners.add(token)) {
            return Optional.of(TypeUse.Declared.of(parent.get().canonicalName()));
        }
        try {
            for (TypeHeader scope : scopes(context, header)) {
                if (scope != member) {
                    Optional<TypeUse.Declared> owner = asSuper(lexicalType(scope, pendingOwners), Optional.of(scope),
                            parent.get(), identities(), pendingOwners);
                    if (owner.isPresent()) {
                        return owner.map((TypeUse.Declared value) -> (TypeUse) value);
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

    /**
     * Specializes an inherited inner type's actual declaring owner, including reordered type arguments: {@code use},
     * a use of {@code declaration}, specialized as the supertype of it that is {@code target}. Each supertype met on
     * the way is the type the header that spells it resolves it to, so a type is never looked up again by its
     * canonical name, which a type of the other package, named or unnamed, may share. A use whose type no header
     * resolved, such as an implicit owner, is taken as {@code target} when its name is the name of {@code target}.
     * {@code visited} holds the types met so far, by identity.
     */
    private Optional<TypeUse.Declared> asSuper(TypeUse.Declared use, Optional<TypeHeader> declaration,
            TypeHeader target, Set<TypeHeader> visited, Set<String> pendingOwners) {
        if (declaration.isEmpty()) {
            return use.name().equals(target.canonicalName()) ? Optional.of(use) : Optional.empty();
        }
        TypeHeader header = declaration.get();
        if (header == target) {
            return Optional.of(use);
        }
        if (!visited.add(header)) {
            return Optional.empty();
        }
        Map<TypeParameterKey, TypeUse> environment = new LinkedHashMap<>();
        bindOwner(use, header, environment);
        for (TypeUse supertype : header.supertypes()) {
            if (!(supertype instanceof TypeUse.Declared spelled)) {
                continue;
            }
            TypeUse specialized = substitute(canonicalize(header, supertype, true, pendingOwners), environment);
            if (specialized instanceof TypeUse.Declared declared) {
                if (!header.typeParameters().isEmpty() && use.arguments().isEmpty()) {
                    declared = new TypeUse.Declared(declared.name(), List.of(), Optional.empty());
                }
                Optional<TypeUse.Declared> result = asSuper(declared,
                        lookup(header, spelled.name(), true, new HashSet<>()), target, visited, pendingOwners);
                if (result.isPresent()) {
                    return result;
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Binds the type parameters of {@code declaration} to the arguments {@code use} gives them, and those of the
     * declarations enclosing it to the arguments of the owners of {@code use}.
     */
    private void bindOwner(TypeUse.Declared use, TypeHeader declaration, Map<TypeParameterKey, TypeUse> environment) {
        use.owner().filter(TypeUse.Declared.class::isInstance).map(TypeUse.Declared.class::cast)
                .ifPresent((TypeUse.Declared owner) -> enclosingDeclaration(declaration)
                        .ifPresent((TypeHeader enclosing) -> bindOwner(owner, enclosing, environment)));
        for (int at = 0; at < Math.min(declaration.typeParameters().size(), use.arguments().size()); at++) {
            environment.put(declaration.parameterKey(at), use.arguments().get(at));
        }
    }

    /** An empty set of types that tells two of them apart by identity, as two types can share a canonical name. */
    private static Set<TypeHeader> identities() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    /**
     * Which side of a canonical name {@code header} is on, a named package or the unnamed package. A recursion guard
     * keys a type by its side and its canonical name, so that the two types one canonical name can name never share a
     * guard.
     */
    private String side(TypeHeader header) {
        return packageOf(header).isEmpty() ? "unnamed:" : "named:";
    }

    /** Applies {@code environment} to every type variable of {@code use}, owners and arguments included. */
    public TypeUse substitute(@NonNull TypeUse use, @NonNull Map<TypeParameterKey, TypeUse> environment) {
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
     * {@code annotationType}, an annotation type of a named package. An annotation whose last segment cannot match is
     * never resolved, so a marker the generator is still about to produce costs nothing here. A type of the unnamed
     * package that shares the canonical name of {@code annotationType}, which code of that package means by that name
     * when a class of it obscures the package, is another type and never a marker.
     *
     * @throws CodegenException {@code SG601} when a marker spelled like {@code annotationType} resolves to nothing
     *     and {@code annotationType} itself is not on the classpath either, which means the generator cannot
     *     recognize any marker: graphmaquina-core is missing from the classpath of the request
     */
    public boolean marks(@NonNull TypeHeader context, @NonNull TypeHeader.Marker marker,
            @NonNull String annotationType) {
        return marks(context, marker, annotationType, false);
    }

    /**
     * {@link #marks} for a marker written on {@code declaration} itself, which javac resolves as
     * {@link #resolveInHeader} does: a member type of the declaration named like the marker never hides it.
     */
    boolean marksInHeader(@NonNull TypeHeader declaration, @NonNull TypeHeader.Marker marker,
            @NonNull String annotationType) {
        return marks(declaration, marker, annotationType, true);
    }

    /**
     * {@link #marks} for {@code local}, written in a local or anonymous class that the body of {@code declaration}
     * declares. javac reads its name in the bodies of the classes that enclose it there before the body of
     * {@code declaration}: a member type one of them declares, or inherits from a supertype it spells, takes the name
     * over, and is never a marker. A name that starts with a local class the member declares, which may be in scope
     * there, is not taken for {@code annotationType} either. Nor is one below a class whose supertype the index cannot
     * tell, because it is such a local class, a member of the type of an expression or a type that neither the sources
     * nor the classpath declare, since that supertype may declare such a member type as well. The refusals this serves
     * only spare the user a marker that would generate or export nothing, so a marker the generator cannot read is
     * let through rather than refused.
     */
    boolean marksInLocalClass(@NonNull TypeHeader declaration, @NonNull TypeHeader.LocalMarker local,
            @NonNull String annotationType) {
        if (!marks(declaration, local.marker(), annotationType, false)) {
            return false;
        }
        String head = local.marker().name().split("\\.", -1)[0];
        return !local.localTypes().contains(head)
                && !localBinding(declaration, local, 0, head, new HashSet<>()).bound();
    }

    /**
     * What {@code name}, a simple name, means in the class bodies of {@code local} from the one at {@code from}
     * outwards, as far as the index can tell: a member type one of them declares or inherits, the first one found.
     */
    private LocalBinding localBinding(TypeHeader declaration, TypeHeader.LocalMarker local, int from, String name,
            Set<String> pending) {
        for (int at = from; at < local.scopes().size(); at++) {
            TypeHeader.LocalScope scope = local.scopes().get(at);
            if (scope.memberTypes().contains(name) || scope.supertypes().isEmpty()) {
                return LocalBinding.UNKNOWN;
            }
            for (String supertype : scope.supertypes().get()) {
                Optional<TypeHeader> resolved = resolveLocally(declaration, local, at + 1, supertype, pending);
                if (resolved.isEmpty()) {
                    return LocalBinding.UNKNOWN;
                }
                Optional<TypeHeader> member = memberType(resolved.get(), name, pending)
                        .filter((TypeHeader type) -> inheritedBy(type, declaration));
                if (member.isPresent()) {
                    return new LocalBinding(true, member);
                }
            }
        }
        return LocalBinding.FREE;
    }

    /**
     * The type {@code spelledName} names in the class bodies of {@code local} from the one at {@code from} outwards,
     * and then in the body of {@code declaration}; empty when the index cannot tell it.
     */
    private Optional<TypeHeader> resolveLocally(TypeHeader declaration, TypeHeader.LocalMarker local, int from,
            String spelledName, Set<String> pending) {
        List<String> segments = List.of(spelledName.split("\\.", -1));
        if (local.localTypes().contains(segments.get(0))) {
            return Optional.empty();
        }
        LocalBinding head = localBinding(declaration, local, from, segments.get(0), pending);
        if (!head.bound()) {
            return lookup(declaration, spelledName, false, pending);
        }
        return head.type().flatMap((TypeHeader type) -> walk(type, segments.subList(1, segments.size()), pending));
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
                    .map((SourceLocation origin) -> new CodegenException(UNRESOLVED, message, origin))
                    .orElseGet(() -> new CodegenException(UNRESOLVED, message));
        }
        return resolved.map((TypeHeader type) -> type.canonicalName().equals(annotationType)
                && !packageOf(type).isEmpty()).orElse(Boolean.FALSE);
    }

    @Override
    public void close() {
        classpath.close();
    }

    /**
     * Resolves {@code spelledName} in the body of {@code context}, or in its header when {@code header} is set. A
     * binary spells every name canonically. A qualified name that a binary of a named package spells, or that the class
     * file of a binary of the unnamed package gives a type of a named package, starts with a package: it is looked up
     * as qualified only, never through a type in scope.
     *
     * @throws CodegenException {@code SG603} when the class file of a binary of the unnamed package gives
     *     {@code spelledName} both to a type of a named package and to a type of the unnamed package
     */
    private Optional<TypeHeader> lookup(TypeHeader context, String spelledName, boolean header,
            Set<String> pending) {
        List<String> segments = List.of(spelledName.split("\\.", -1));
        if (segments.size() == 1) {
            return simple(context, spelledName, header, pending);
        }
        boolean binary = context.origin().isEmpty();
        if (binary && classpath.namesBothTypes(context, spelledName)) {
            throw new CodegenException(COLLISION, "the class file of " + context.canonicalName()
                    + " of the unnamed package names both "
                    + declared(spelledName, false).map(this::describe).orElse(spelledName) + " and "
                    + declared(spelledName, true).map(this::describe).orElse(spelledName)
                    + ", which the generator cannot tell apart by their canonical name; rename one of them");
        }
        Optional<TypeHeader> head = binary
                && (!packageOf(context).isEmpty() || classpath.namesNamedType(context, spelledName))
                ? Optional.empty()
                : simple(context, segments.get(0), header, pending);
        if (head.isPresent()) {
            Optional<TypeHeader> walked = walk(head.get(), segments.subList(1, segments.size()), pending);
            if (walked.isPresent()) {
                return walked;
            }
        }
        return qualified(segments, pending);
    }

    private Optional<TypeHeader> simple(TypeHeader context, String name, boolean header, Set<String> pending) {
        String token = (header ? "header:" : "simple:") + side(context) + context.canonicalName() + '#' + name;
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
                    .map((String single) -> qualified(List.of(single.split("\\.", -1)), pending)
                            .filter((TypeHeader type) -> importable(type, scope.packageName())))
                    .toList(), name, context);
            if (imported.isPresent()) {
                return imported;
            }
            // In the unnamed package, a sibling's canonical name is its simple name. Code of a named package never
            // sees a type of the unnamed package, and a binary keeps its package outside its scope.
            String packageName = packageOf(context);
            Optional<TypeHeader> sibling =
                    packageName.isEmpty() ? find(name) : declared(packageName + '.' + name, false);
            if (sibling.isPresent()) {
                return sibling;
            }
            Optional<TypeHeader> onDemand = unique(scope.onDemandImports().stream()
                    .map((String prefix) -> qualified(List.of((prefix + '.' + name).split("\\.", -1)), pending)
                            .filter((TypeHeader type) -> importable(type, scope.packageName())))
                    .toList(), name, context);
            if (onDemand.isPresent()) {
                return onDemand;
            }
            return declared("java.lang." + name, false)
                    .filter((TypeHeader type) -> importable(type, scope.packageName()));
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
    Optional<String> inaccessibility(@NonNull TypeHeader type, @NonNull String packageName) {
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
    void requireUnobscured(@NonNull String packageName, @NonNull String unit, @NonNull List<String> names,
            @NonNull SourceLocation origin) {
        for (String name : names) {
            String segment = name.substring(0, name.indexOf('.'));
            Optional<TypeHeader> obscurer = find(packageName.isEmpty() ? segment : packageName + '.' + segment)
                    .filter((TypeHeader type) -> packageOf(type).equals(packageName))
                    .or(() -> declared("java.lang." + segment, false)
                            .filter((TypeHeader type) -> importable(type, packageName)));
            if (obscurer.isPresent()) {
                throw new CodegenException(COLLISION, unit + " cannot name " + name + ": the type "
                        + obscurer.get().canonicalName() + " obscures the package " + segment + " there", origin);
            }
        }
    }

    /**
     * The declaration that directly encloses {@code header}, empty for a top-level type. It belongs to the package of
     * {@code header}, and is looked up there.
     */
    private Optional<TypeHeader> enclosingDeclaration(TypeHeader header) {
        String packageName = packageOf(header);
        int separator = header.canonicalName().lastIndexOf('.');
        return separator > packageName.length()
                ? declared(header.canonicalName().substring(0, separator), packageName.isEmpty())
                : Optional.empty();
    }

    /**
     * {@code type} as a diagnostic names it apart from a type of the same canonical name: that name, the package that
     * declares it, and the position of its declaration, or that the classpath declares it.
     */
    String describe(TypeHeader type) {
        String packageName = packageOf(type);
        return type.canonicalName() + " of "
                + (packageName.isEmpty() ? "the unnamed package" : "package " + packageName)
                + type.origin().map((SourceLocation origin) -> " at " + origin.path() + ':' + origin.line())
                        .orElse(" on the classpath");
    }

    /**
     * The package that declares {@code header}, empty for the unnamed package: as its source spells it, or, for a
     * header of the classpath, as {@link ClasspathDeclarations#packageOf} gives it.
     */
    String packageOf(TypeHeader header) {
        return header.origin().isPresent()
                ? header.scope().packageName()
                : classpath.packageOf(header);
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

    /**
     * The type a qualified name means whose first segment names a package, as an import, a name no type in scope
     * starts and a name a binary of a named package spells are read: a type of a named package that a prefix of
     * {@code segments} names, the longest first, followed through the member types it declares or inherits under the
     * remaining segments. No class of the unnamed package is in scope in an import (JLS 6.3), and code of a named
     * package never sees one, so a type of the unnamed package is never answered.
     */
    private Optional<TypeHeader> qualified(List<String> segments, Set<String> pending) {
        for (int length = segments.size(); length >= 2; length--) {
            Optional<TypeHeader> owner = declared(String.join(".", segments.subList(0, length)), false);
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
     * the declaring header found here is the one whose canonical name is recorded. A member type {@code owner}
     * declares belongs to the package of {@code owner}, and is looked up there.
     */
    private Optional<TypeHeader> memberType(TypeHeader owner, String simpleName, Set<String> pending) {
        String token = "member:" + side(owner) + owner.canonicalName() + '.' + simpleName;
        if (!pending.add(token)) {
            return Optional.empty();
        }
        try {
            Optional<TypeHeader> declared =
                    declared(owner.canonicalName() + '.' + simpleName, packageOf(owner).isEmpty());
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
                        .filter((TypeHeader member) -> inheritedBy(member, owner));
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

    /**
     * The declaration itself and the source declarations that lexically enclose it, innermost first. They belong to
     * the package of the declaration: a class of the unnamed package named like the first segment of a named package
     * never encloses a declaration of that package.
     */
    private List<TypeHeader> enclosingChain(TypeHeader context) {
        List<TypeHeader> chain = new ArrayList<>();
        chain.add(context);
        Map<String, TypeHeader> side = packageOf(context).isEmpty() ? unnamedSources : sources;
        String enclosing = context.canonicalName();
        while (enclosing.lastIndexOf('.') > 0) {
            enclosing = enclosing.substring(0, enclosing.lastIndexOf('.'));
            TypeHeader header = side.get(enclosing);
            if (header == null) {
                break;
            }
            chain.add(header);
        }
        return chain;
    }

    /**
     * What a simple name means in the class bodies between a member and a local marker.
     *
     * @param bound whether a member type of one of those classes takes the name over, or may, as far as the index
     *     can tell
     * @param type that member type, when the index declares it
     */
    private record LocalBinding(boolean bound, Optional<TypeHeader> type) {

        /** No class between takes the name over: the body of the declaration decides what it means. */
        private static final LocalBinding FREE = new LocalBinding(false, Optional.empty());
        /** A class between takes the name over, or may, with a type the index does not declare. */
        private static final LocalBinding UNKNOWN = new LocalBinding(true, Optional.empty());
    }
}
