package io.github.graphmaquina.codegen.source;

import io.github.graphmaquina.annotation.internal.SymbolKind;
import io.github.graphmaquina.codegen.CodegenException;
import io.github.graphmaquina.codegen.emit.SymbolNames;
import io.github.graphmaquina.codegen.model.MemberDescriptor;
import io.github.graphmaquina.codegen.model.PropertyDescriptor;
import io.github.graphmaquina.codegen.model.SourceLocation;
import io.github.graphmaquina.codegen.model.SymbolDescriptor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.IdentityHashMap;
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
 * Discovers the members and properties the original sources export, one symbol per host and member name.
 *
 * <p>A host exports its own marked declarations; a host that also carries {@code @GraphBindings} exports the
 * marked declarations it inherits, from a source or a binary supertype, under its own name. Generated code calls a
 * static binding through the host, so a static method the host does not inherit is not exported through it: a
 * static method of an interface, which no type inherits, or one that the host or a superclass between hides with a
 * method of the same name and the same erased parameter types. Every marked method
 * is kept as its own candidate, with the type that declares it, the role that declaration spells and its own
 * origin, or the host's for one read from a binary. A property keeps only the first declaration of its logical
 * name, the host's own before inherited ones, and two the host declares itself are refused with {@code SG603}.
 * Nothing here decides an override: the bootstrap cannot see a source host from the binary task, so a role is never
 * transferred onto a same-name local declaration and a family is never rejected for mixing roles. Resolving
 * overrides and effective signatures is the processor's work, once javac has the real elements.</p>
 */
public final class ExportDeclarations {

    private static final String COLLISION = "SG603";
    private static final String INVALID_ROLE = "SG604";
    private static final String BINDINGS = "io.github.graphmaquina.annotation.GraphBindings";
    /** The package of the StateGraph types every references container writes by qualified name. */
    private static final String STATEGRAPH_PACKAGE = "io.github.graphmaquina";
    /** The annotation every references container carries, written by qualified name. */
    private static final String SUPPRESS_WARNINGS = "java.lang.SuppressWarnings";
    private static final Map<String, SymbolKind> ROLES = roles();

    private ExportDeclarations() {
    }

    /**
     * Every symbol the original sources of {@code index} export, ordered by qualified name so that neither the
     * order of the files nor the order of a hash table can change the result. A host its references container
     * cannot name, because it is private or nested in a private type, is refused with {@code SG604}. A host is
     * refused with {@code SG603} when a qualified name its container writes, StateGraph's {@code io} package,
     * {@code java.lang.SuppressWarnings} or, for a host of a named package, the host's own name, starts with the
     * simple name of a type the host's package declares or of a public type of {@code java.lang}: inside the
     * container that type obscures the package.
     */
    public static List<SymbolDescriptor> collect(DeclarationIndex index) {
        Objects.requireNonNull(index, "index");
        List<TypeHeader> hosts = new ArrayList<>(index.sourceHeaders());
        hosts.sort(Comparator.comparing(TypeHeader::canonicalName));
        Map<String, TypeHeader> containers = new LinkedHashMap<>();
        List<SymbolDescriptor> symbols = new ArrayList<>();
        for (TypeHeader host : hosts) {
            List<SymbolDescriptor> exported = exports(index, host);
            if (exported.isEmpty()) {
                continue;
            }
            String container = container(host);
            String packageName = host.scope().packageName();
            Optional<String> inaccessible = index.inaccessibility(host, packageName);
            if (inaccessible.isPresent()) {
                throw new CodegenException(INVALID_ROLE, "the host " + host.canonicalName()
                    + " is not accessible to its references container " + container + ": " + inaccessible.get(),
                    origin(host));
            }
            index.requireUnobscured(packageName, "the references container generated for the host "
                + host.canonicalName() + " in " + (packageName.isEmpty() ? "the unnamed package" : "package "
                + packageName), packageName.isEmpty() ? List.of(STATEGRAPH_PACKAGE, SUPPRESS_WARNINGS)
                : List.of(STATEGRAPH_PACKAGE, SUPPRESS_WARNINGS, host.canonicalName()), origin(host));
            TypeHeader previous = containers.putIfAbsent(container, host);
            if (previous != null) {
                throw sharedContainer(container, previous, host);
            }
            symbols.addAll(exported);
        }
        symbols.sort(Comparator.comparing(SymbolDescriptor::qualifiedName));
        return List.copyOf(symbols);
    }

    /** The symbols of one host: its method families first, then its properties. */
    private static List<SymbolDescriptor> exports(DeclarationIndex index, TypeHeader host) {
        Map<String, List<Declaration>> methods = new LinkedHashMap<>();
        Map<String, Declaration> properties = new LinkedHashMap<>();
        for (Declaration declaration : declarations(index, host)) {
            if (declaration.role() != SymbolKind.PROPERTY) {
                methods.computeIfAbsent(declaration.member().name(), name -> new ArrayList<>())
                        .add(declaration);
                continue;
            }
            String logicalName = logicalName(declaration);
            Declaration previous = properties.putIfAbsent(logicalName, declaration);
            if (previous != null && previous.local() && declaration.local()) {
                throw duplicateProperty(host, logicalName, previous, declaration);
            }
        }
        List<SymbolDescriptor> exported = new ArrayList<>();
        for (Map.Entry<String, List<Declaration>> method : methods.entrySet()) {
            if (properties.containsKey(method.getKey())) {
                throw sharedSymbol(host, method.getKey());
            }
            exported.add(method(index, host, method.getKey(), method.getValue()));
        }
        for (Map.Entry<String, Declaration> property : properties.entrySet()) {
            exported.add(property(host, property.getKey(), property.getValue()));
        }
        if (exported.isEmpty()) {
            return exported;
        }
        Map<String, SymbolDescriptor> names = new LinkedHashMap<>();
        String container = container(host);
        String simpleContainer = container.substring(container.lastIndexOf('.') + 1);
        for (SymbolDescriptor symbol : exported) {
            String simpleName = symbol.qualifiedName().substring(symbol.qualifiedName().lastIndexOf('.') + 1);
            if (simpleName.equals(simpleContainer)) {
                throw new CodegenException(COLLISION, "the member " + symbol.member() + " of "
                    + host.canonicalName() + " at " + at(symbol.origin()) + " normalizes to its own references "
                    + "container " + container + " generated for " + host.canonicalName() + " at "
                    + at(origin(host)), symbol.origin());
            }
            SymbolDescriptor previous = names.putIfAbsent(symbol.qualifiedName(), symbol);
            if (previous != null) {
                throw new CodegenException(COLLISION, "the generated symbol " + symbol.qualifiedName()
                    + " is claimed by " + previous.host() + '#' + previous.member() + " at "
                    + at(previous.origin()) + " and " + symbol.host() + '#' + symbol.member() + " at "
                    + at(symbol.origin()), symbol.origin());
            }
        }
        return exported;
    }

    /**
     * The marked declarations {@code host} exports, its own first and, when it carries {@code @GraphBindings},
     * the inherited ones in the order its hierarchy declares them, without the static methods it does not inherit,
     * as {@link #inherits} tells them. A declaration reached twice through a
     * diamond is kept once; that deduplication is not a second local declaration. The hierarchy can hold two types of
     * one canonical name, a type of a named package and a type of the unnamed package. The declarations of each are
     * exported, but a member both declare with one name and with parameter types of the same canonical names is kept
     * once, as the type met first declares it.
     */
    private static List<Declaration> declarations(DeclarationIndex index, TypeHeader host) {
        List<Declaration> declarations = new ArrayList<>();
        Set<String> signatures = new LinkedHashSet<>();
        declare(index, host, host, true, declarations, signatures, List.of());
        if (host.markers().stream()
                .anyMatch((TypeHeader.Marker marker) -> index.marksInHeader(host, marker, BINDINGS))) {
            List<TypeHeader> superclasses = superclasses(index, host);
            for (TypeHeader ancestor : ancestors(index, host)) {
                declare(index, host, ancestor, false, declarations, signatures, superclasses);
            }
        }
        return declarations;
    }

    private static void declare(DeclarationIndex index, TypeHeader host, TypeHeader declaring, boolean local,
            List<Declaration> declarations, Set<String> signatures, List<TypeHeader> superclasses) {
        for (TypeHeader.Member member : declaring.members()) {
            if (!local && !inherits(index, declaring, member, superclasses)) {
                continue;
            }
            Optional<SymbolKind> role = role(index, host, declaring, member);
            if (role.isPresent() && signatures.add(signature(index, declaring, member))) {
                declarations.add(new Declaration(declaring, member, role.get(), local));
            }
        }
    }

    /** The one role {@code member} spells, or empty when it spells none; two roles are a contradiction. */
    private static Optional<SymbolKind> role(DeclarationIndex index, TypeHeader host, TypeHeader declaring,
            TypeHeader.Member member) {
        List<SymbolKind> declared = new ArrayList<>();
        for (TypeHeader.Marker marker : member.markers()) {
            for (Map.Entry<String, SymbolKind> role : ROLES.entrySet()) {
                if (!declared.contains(role.getValue()) && index.marks(declaring, marker, role.getKey())) {
                    declared.add(role.getValue());
                }
            }
        }
        if (declared.size() > 1) {
            throw new CodegenException(INVALID_ROLE, "the member " + member.name() + " of "
                    + declaring.canonicalName() + " declares more than one graph role: "
                    + String.join(" and ", declared.stream().map(SymbolKind::name).sorted().toList()),
                    member.origin().orElseGet(() -> origin(host)));
        }
        return declared.isEmpty() ? Optional.empty() : Optional.of(declared.get(0));
    }

    /**
     * Whether the host whose superclass chain {@code superclasses} is, nearest first and the host included, inherits
     * {@code member} of its supertype {@code declaring}, as far as the headers tell. An instance member is inherited,
     * or overridden by a member that keeps its role. A static method is not inherited when an interface declares it,
     * since neither a class nor an interface inherits a static method of an interface, nor when a type of the chain
     * below {@code declaring} declares a method of its name whose parameter types have the same erasures, which hides
     * it. A parameter whose erasure a header does not tell, a type variable, matches none, so such a method is still
     * exported and the processor, which knows its bounds, refuses it if it is hidden.
     */
    private static boolean inherits(DeclarationIndex index, TypeHeader declaring, TypeHeader.Member member,
            List<TypeHeader> superclasses) {
        if (member.kind() != ElementKind.METHOD || !member.modifiers().contains(Modifier.STATIC)) {
            return true;
        }
        if (declaring.kind() == ElementKind.INTERFACE) {
            return false;
        }
        List<Optional<String>> erasures = erasures(index, declaring, member);
        for (TypeHeader below : superclasses) {
            if (below == declaring) {
                return true;
            }
            for (TypeHeader.Member other : below.members()) {
                if (other.kind() == ElementKind.METHOD && other.name().equals(member.name())
                        && sameKnownErasures(erasures, erasures(index, below, other))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** {@code host} and its superclasses, nearest first, as far as a header declares each of them. */
    private static List<TypeHeader> superclasses(DeclarationIndex index, TypeHeader host) {
        List<TypeHeader> chain = new ArrayList<>();
        Set<TypeHeader> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Optional<TypeHeader> current = Optional.of(host);
        while (current.isPresent() && visited.add(current.get())) {
            TypeHeader type = current.get();
            chain.add(type);
            current = type.supertypes().stream()
                    .filter((TypeUse supertype) -> supertype instanceof TypeUse.Declared)
                    .map((TypeUse supertype) -> index.resolveInHeader(type, ((TypeUse.Declared) supertype).name()))
                    .flatMap(Optional::stream)
                    .filter((TypeHeader supertype) -> supertype.kind() == ElementKind.CLASS)
                    .findFirst();
        }
        return chain;
    }

    /** The erasure of each parameter type of {@code member}, empty where a header does not tell it. */
    private static List<Optional<String>> erasures(DeclarationIndex index, TypeHeader declaring,
            TypeHeader.Member member) {
        List<Optional<String>> erasures = new ArrayList<>();
        for (TypeUse parameter : member.parameterTypes()) {
            erasures.add(erasure(index.canonicalize(declaring, parameter)));
        }
        return erasures;
    }

    /** The erasure of a canonical use: its class or primitive, with its array dimensions; empty for a variable. */
    private static Optional<String> erasure(TypeUse use) {
        return switch (use) {
            case TypeUse.Declared declared -> Optional.of(declared.name());
            case TypeUse.Array array -> erasure(array.component()).map((String component) -> component + "[]");
            case TypeUse.Primitive primitive -> Optional.of(primitive.name());
            case TypeUse.Variable variable -> Optional.empty();
            case TypeUse.Wildcard wildcard -> Optional.empty();
        };
    }

    /** Whether two parameter lists have the same length and the same erasures, every one of them known. */
    private static boolean sameKnownErasures(List<Optional<String>> first, List<Optional<String>> second) {
        if (first.size() != second.size()) {
            return false;
        }
        for (int at = 0; at < first.size(); at++) {
            if (first.get(at).isEmpty() || !first.get(at).equals(second.get(at))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The supertypes of {@code host}, nearest first, skipping the ones no header declares. Each is met once, and
     * told apart by identity from a type of the other package of its canonical name, named or unnamed.
     */
    private static List<TypeHeader> ancestors(DeclarationIndex index, TypeHeader host) {
        List<TypeHeader> ancestors = new ArrayList<>();
        Set<TypeHeader> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        visited.add(host);
        Deque<TypeHeader> pending = new ArrayDeque<>();
        pending.addLast(host);
        while (!pending.isEmpty()) {
            TypeHeader current = pending.removeFirst();
            for (TypeUse supertype : current.supertypes()) {
                if (!(supertype instanceof TypeUse.Declared declared)) {
                    continue;
                }
                Optional<TypeHeader> resolved = index.resolveInHeader(current, declared.name());
                if (resolved.isEmpty() || !visited.add(resolved.get())) {
                    continue;
                }
                ancestors.add(resolved.get());
                pending.addLast(resolved.get());
            }
        }
        return ancestors;
    }

    private static SymbolDescriptor method(DeclarationIndex index, TypeHeader host, String member,
            List<Declaration> declarations) {
        List<MemberDescriptor> candidates = new ArrayList<>();
        Set<SymbolKind> kinds = EnumSet.noneOf(SymbolKind.class);
        for (Declaration declaration : declarations) {
            candidates.add(candidate(index, host, declaration));
            kinds.add(declaration.role());
        }
        return new SymbolDescriptor(qualifiedName(host, member, candidates.get(0).origin()), host.canonicalName(), member, kinds,
                candidates, Optional.empty(), candidates.get(0).origin());
    }

    /**
     * One candidate, typed as its declaration spells it, with every type name the index resolves replaced by its
     * canonical name. Type variables are kept, never substituted: the host's specialization is the processor's to
     * apply. A declaration read from a binary has no original source, so it is anchored at the host that exports it
     * while {@code declaringType} keeps its provenance.
     */
    private static MemberDescriptor candidate(DeclarationIndex index, TypeHeader host,
            Declaration declaration) {
        TypeHeader declaring = declaration.declaring();
        TypeHeader.Member member = declaration.member();
        List<String> parameterTypes = new ArrayList<>();
        for (TypeUse parameter : member.parameterTypes()) {
            parameterTypes.add(index.canonicalize(declaring, parameter).render());
        }
        return new MemberDescriptor(declaring.canonicalName(), member.name(), parameterTypes,
                index.canonicalize(declaring, member.type()).render(), declaration.role(),
                member.modifiers().contains(Modifier.STATIC),
                member.origin().orElseGet(() -> origin(host)));
    }

    /**
     * One property. A marked field exports the logical property of its own exact name; a marked getter exports
     * that reader. Which accessors actually exist is checked later, so a private field is metadata only.
     */
    private static SymbolDescriptor property(TypeHeader host, String logicalName, Declaration declaration) {
        SourceLocation origin = declaration.member().origin().orElseGet(() -> origin(host));
        return new SymbolDescriptor(qualifiedName(host, logicalName, origin), host.canonicalName(), logicalName,
                Set.of(SymbolKind.PROPERTY), List.of(),
                Optional.of(new PropertyDescriptor(declaration.declaring().canonicalName(), logicalName,
                        declaration.member().name(), isGetter(declaration), origin)),
                origin);
    }

    private static String logicalName(Declaration declaration) {
        return isGetter(declaration)
                ? PropertyNames.fromGetter(declaration.member().name())
                : declaration.member().name();
    }

    private static boolean isGetter(Declaration declaration) {
        return declaration.member().kind() == ElementKind.METHOD;
    }

    private static String qualifiedName(TypeHeader host, String member, SourceLocation memberOrigin) {
        String container = container(host);
        try {
            return container + '.' + SymbolNames.typeName(SymbolNames.hostName(hostName(host)), member);
        } catch (IllegalArgumentException invalid) {
            throw new CodegenException(COLLISION, "the member " + member + " of " + host.canonicalName()
                + ": " + invalid.getMessage(), memberOrigin);
        }
    }

    /** The canonical name of the references container of {@code host}, a top-level type of its package. */
    private static String container(TypeHeader host) {
        String packageName = host.scope().packageName();
        try {
            String container = SymbolNames.containerName(hostName(host));
            return SymbolNames.qualified(packageName, container);
        } catch (IllegalArgumentException invalid) {
            throw new CodegenException(COLLISION, "the host " + host.canonicalName() + ": "
                + invalid.getMessage(), origin(host));
        }
    }

    /** The canonical name of {@code host} without its package, which for a nested host is still dotted. */
    private static String hostName(TypeHeader host) {
        String packageName = host.scope().packageName();
        return packageName.isEmpty()
                ? host.canonicalName()
                : host.canonicalName().substring(packageName.length() + 1);
    }

    /**
     * Identifies a declaration by the type that declares it, so an override is never merged into its base, and
     * by its kind, so a field never shares a key with a same-named method that declares no parameter.
     */
    private static String signature(DeclarationIndex index, TypeHeader declaring, TypeHeader.Member member) {
        List<String> parameterTypes = new ArrayList<>();
        for (TypeUse parameter : member.parameterTypes()) {
            parameterTypes.add(index.canonicalize(declaring, parameter).render());
        }
        return declaring.canonicalName() + '#' + member.kind() + ':' + member.name()
                + '(' + String.join(",", parameterTypes) + ')';
    }

    /**
     * The anchor of a host. Only original sources are hosts, so this is the fallback for a declaration read
     * from a binary, which has no source position of its own.
     */
    private static SourceLocation origin(TypeHeader host) {
        return host.origin().orElseThrow(() -> new IllegalStateException(
                "the host " + host.canonicalName() + " is not an original source declaration"));
    }

    private static CodegenException sharedContainer(String container, TypeHeader first, TypeHeader second) {
        return new CodegenException(COLLISION, "the references container " + container
                + " is generated for both " + first.canonicalName() + " at " + at(origin(first)) + " and "
                + second.canonicalName() + " at " + at(origin(second)), origin(first));
    }

    private static CodegenException duplicateProperty(TypeHeader host, String logicalName, Declaration first,
            Declaration second) {
        return new CodegenException(COLLISION, "the property " + logicalName + " of " + host.canonicalName()
                + " is declared twice, by " + describe(first) + " and by " + describe(second),
                second.member().origin().orElseGet(() -> origin(host)));
    }

    /** Names one declaration by kind, so two declarations that spell the same name stay distinguishable. */
    private static String describe(Declaration declaration) {
        return (isGetter(declaration) ? "the method " : "the field ") + declaration.member().name();
    }

    private static CodegenException sharedSymbol(TypeHeader host, String member) {
        return new CodegenException(COLLISION, "the symbol " + member + " of " + host.canonicalName()
                + " is exported as both a method and a property", origin(host));
    }

    private static String at(SourceLocation origin) {
        return origin.path() + ':' + origin.line();
    }

    private static Map<String, SymbolKind> roles() {
        Map<String, SymbolKind> roles = new LinkedHashMap<>();
        roles.put("io.github.graphmaquina.annotation.GraphAction", SymbolKind.ACTION);
        roles.put("io.github.graphmaquina.annotation.GraphGuard", SymbolKind.GUARD);
        roles.put("io.github.graphmaquina.annotation.GraphHook", SymbolKind.HOOK);
        roles.put("io.github.graphmaquina.annotation.GraphCondition", SymbolKind.CONDITION);
        roles.put("io.github.graphmaquina.annotation.GraphProperty", SymbolKind.PROPERTY);
        roles.put("io.github.graphmaquina.annotation.GraphLookup", SymbolKind.LOOKUP);
        return Collections.unmodifiableMap(roles);
    }

    /**
     * One marked declaration a host exports: the header that declares it, the member itself, the role that
     * declaration spells and whether the exporting host is the one that declares it.
     */
    private record Declaration(TypeHeader declaring, TypeHeader.Member member, SymbolKind role,
            boolean local) {
    }
}
