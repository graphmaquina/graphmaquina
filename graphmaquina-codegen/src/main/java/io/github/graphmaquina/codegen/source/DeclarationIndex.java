package io.github.graphmaquina.codegen.source;

import io.github.graphmaquina.codegen.CodegenException;
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

    private final Map<String, TypeHeader> sources;
    private final ClasspathDeclarations classpath;

    private DeclarationIndex(Map<String, TypeHeader> sources, ClasspathDeclarations classpath) {
        this.sources = sources;
        this.classpath = classpath;
    }

    /** Reads every declaration of {@code units} and opens the classpath headers they resolve against. */
    public static DeclarationIndex open(List<SourceUnit> units, List<Path> classpath) {
        Objects.requireNonNull(units, "units");
        Objects.requireNonNull(classpath, "classpath");
        ClasspathDeclarations declarations = ClasspathDeclarations.open(classpath);
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

    /** Every header read from the original sources, in the order the units declared them. */
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
     * Resolves {@code spelledName} as {@code context} spells it: enclosing and inherited member types first,
     * then single imports, the declaring package, on-demand imports and finally {@code java.lang}. A name
     * that two imports offer at once is reported as {@code SG601} instead of being chosen by order.
     */
    public Optional<TypeHeader> resolve(TypeHeader context, String spelledName) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(spelledName, "spelledName");
        return lookup(context, spelledName, new HashSet<>());
    }

    /** Replaces every spelled name in {@code use} by its canonical name, leaving unresolvable names alone. */
    public TypeUse canonicalize(TypeHeader context, TypeUse use) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(use, "use");
        return switch (use) {
            case TypeUse.Declared declared -> {
                List<TypeUse> arguments = new ArrayList<>();
                for (TypeUse argument : declared.arguments()) {
                    arguments.add(canonicalize(context, argument));
                }
                Optional<TypeHeader> resolved = resolve(context, declared.name());
                Optional<TypeUse> owner = declared.owner().map(value -> canonicalize(context, value));
                if (resolved.isPresent() && resolved.get().kind() == ElementKind.CLASS
                        && !resolved.get().modifiers().contains(Modifier.STATIC)) {
                    int separator = declared.name().lastIndexOf('.');
                    if (owner.isEmpty()) {
                        owner = separator < 0 ? implicitOwner(context, resolved.get())
                                : resolve(context, declared.name().substring(0, separator))
                                    .map(header -> TypeUse.Declared.of(header.canonicalName()));
                    }
                    String parent = parentName(resolved.get());
                    if (owner.isPresent() && owner.get() instanceof TypeUse.Declared selected) {
                        owner = asSuper(selected, parent, new HashSet<>()).map(value -> (TypeUse) value);
                    }
                }
                yield new TypeUse.Declared(resolved.map(TypeHeader::canonicalName).orElse(declared.name()),
                        arguments, owner);
            }
            case TypeUse.Array array -> new TypeUse.Array(canonicalize(context, array.component()));
            case TypeUse.Wildcard wildcard -> new TypeUse.Wildcard(
                    wildcard.upperBound().map(bound -> canonicalize(context, bound)),
                    wildcard.lowerBound().map(bound -> canonicalize(context, bound)));
            case TypeUse.Variable variable -> variable;
            case TypeUse.Primitive primitive -> primitive;
        };
    }

    /** An unqualified inner type in its enclosing generic declaration implicitly captures that owner's variables. */
    private Optional<TypeUse> implicitOwner(TypeHeader context, TypeHeader member) {
        if (member.kind() != ElementKind.CLASS || member.modifiers().contains(Modifier.STATIC)) {
            return Optional.empty();
        }
        String parent = parentName(member);
        if (find(parent).isEmpty()) {
            return Optional.empty();
        }
        for (TypeHeader scope : enclosingChain(context)) {
            if (!scope.canonicalName().equals(member.canonicalName())) {
                Optional<TypeUse.Declared> owner = asSuper(lexicalType(scope), parent, new HashSet<>());
                if (owner.isPresent()) {
                    return owner.map(value -> (TypeUse) value);
                }
            }
            if (scope.modifiers().contains(Modifier.STATIC)) {
                break;
            }
        }
        return Optional.empty();
    }

    private TypeUse.Declared lexicalType(TypeHeader header) {
        List<TypeUse> variables = new ArrayList<>();
        for (int at = 0; at < header.typeParameters().size(); at++) {
            variables.add(new TypeUse.Variable(header.typeParameters().get(at), header.parameterKey(at)));
        }
        return new TypeUse.Declared(header.canonicalName(), variables, implicitOwner(header, header));
    }

    private static String parentName(TypeHeader header) {
        int separator = header.canonicalName().lastIndexOf('.');
        return separator < 0 ? "" : header.canonicalName().substring(0, separator);
    }

    /** Specializes an inherited inner type's actual declaring owner, including reordered type arguments. */
    private Optional<TypeUse.Declared> asSuper(TypeUse.Declared use, String target, Set<String> visited) {
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
            TypeUse specialized = substitute(canonicalize(header, supertype), environment);
            if (specialized instanceof TypeUse.Declared declared) {
                if (!header.typeParameters().isEmpty() && use.arguments().isEmpty()) {
                    declared = new TypeUse.Declared(declared.name(), List.of(), Optional.empty());
                }
                Optional<TypeUse.Declared> result = asSuper(declared, target, visited);
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
     * Whether {@code marker} names {@code annotationType}. An annotation whose last segment cannot match is
     * never resolved, so a marker the generator is still about to produce costs nothing here.
     */
    public boolean marks(TypeHeader context, TypeHeader.Marker marker, String annotationType) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(marker, "marker");
        Objects.requireNonNull(annotationType, "annotationType");
        String simpleName = annotationType.substring(annotationType.lastIndexOf('.') + 1);
        if (!marker.name().equals(simpleName) && !marker.name().endsWith('.' + simpleName)) {
            return false;
        }
        return resolve(context, marker.name())
                .map(header -> header.canonicalName().equals(annotationType))
                .orElse(Boolean.FALSE);
    }

    @Override
    public void close() {
        classpath.close();
    }

    private Optional<TypeHeader> lookup(TypeHeader context, String spelledName, Set<String> pending) {
        List<String> segments = List.of(spelledName.split("\\.", -1));
        if (segments.size() == 1) {
            return simple(context, spelledName, pending);
        }
        Optional<TypeHeader> head = simple(context, segments.get(0), pending);
        if (head.isPresent()) {
            Optional<TypeHeader> walked = walk(head.get(), segments.subList(1, segments.size()), pending);
            if (walked.isPresent()) {
                return walked;
            }
        }
        return qualified(segments, pending);
    }

    private Optional<TypeHeader> simple(TypeHeader context, String name, Set<String> pending) {
        String token = "simple:" + context.canonicalName() + '#' + name;
        if (!pending.add(token)) {
            return Optional.empty();
        }
        try {
            for (TypeHeader enclosing : enclosingChain(context)) {
                Optional<TypeHeader> member = memberType(enclosing, name, pending);
                if (member.isPresent()) {
                    return member;
                }
            }
            TypeHeader.Scope scope = context.scope();
            Optional<TypeHeader> imported = unique(scope.singleImports().stream()
                    .filter(single -> single.endsWith('.' + name))
                    .map(single -> qualified(List.of(single.split("\\.", -1)), pending))
                    .toList(), name, context);
            if (imported.isPresent()) {
                return imported;
            }
            if (!scope.packageName().isEmpty()) {
                Optional<TypeHeader> sibling = find(scope.packageName() + '.' + name);
                if (sibling.isPresent()) {
                    return sibling;
                }
            }
            Optional<TypeHeader> onDemand = unique(scope.onDemandImports().stream()
                    .map(prefix -> qualified(List.of((prefix + '.' + name).split("\\.", -1)), pending))
                    .toList(), name, context);
            if (onDemand.isPresent()) {
                return onDemand;
            }
            return find("java.lang." + name);
        } finally {
            pending.remove(token);
        }
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
     * A member type of {@code owner} or of one of its supertypes. A dotted spelling is never assumed to be a
     * canonical name: the declaring header found here is the one whose canonical name is recorded.
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
                Optional<TypeHeader> superHeader = lookup(owner, use.name(), pending);
                if (superHeader.isEmpty()) {
                    continue;
                }
                Optional<TypeHeader> inherited = memberType(superHeader.get(), simpleName, pending);
                if (inherited.isPresent()) {
                    return inherited;
                }
            }
            return Optional.empty();
        } finally {
            pending.remove(token);
        }
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
