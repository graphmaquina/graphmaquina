package io.github.graphmaquina.codegen.source;

import io.github.graphmaquina.codegen.CodegenException;
import io.github.graphmaquina.codegen.model.GraphDescriptor;
import io.github.graphmaquina.codegen.model.SourceLocation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.ElementKind;

/**
 * Specializes one machine, the facade marked {@code @GenerateGraphApi}, to the {@code Machine<E, S, T>} it
 * ultimately extends. The supertype chain is walked by declaration identity, substituting each type argument into
 * the parameters the supertype declares, so a generic base contributes its specialization whether it is an original
 * source or a classpath binary. The facade's kind is not checked here, so a class facade is specialized like an
 * interface; the annotation processor is what refuses it, with {@code SG502}.
 */
public final class MachineHeaders {

    private static final String MACHINE = "io.github.graphmaquina.api.Machine";
    private static final String GRAPH_PEERS = "io.github.graphmaquina.annotation.GraphPeers";
    private static final String UNRESOLVED = "SG601";
    private static final String UNUSABLE_PEER = "SG608";
    /** The packages of the StateGraph and JDK types every vocabulary writes by qualified name. */
    private static final List<String> VOCABULARY_PACKAGES = List.of("io.github.graphmaquina", "java.lang");

    private MachineHeaders() {
    }

    /**
     * Resolves the entity, the state enum, the transition enum, the constants of both and the declared peers
     * of {@code facade}. The vocabulary generated for it is a top-level type of the facade's package that names
     * the facade and both enums, so a generic facade, or a facade or enum that package cannot access, is refused
     * with {@code SG601}. A type of that package, or a public type of {@code java.lang}, named like the first segment
     * of a qualified name the vocabulary writes would obscure it there, which is refused with {@code SG603}; the
     * types generated in that package are checked by the emitters.
     */
    public static GraphDescriptor resolve(DeclarationIndex index, String facade) {
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(facade, "facade");
        TypeHeader header = index.find(facade).orElseThrow(() -> new CodegenException(UNRESOLVED,
                "the machine interface " + facade + " is not declared"));
        SourceLocation origin = header.origin().orElseThrow(() -> new CodegenException(UNRESOLVED,
                "the machine interface " + facade + " is not an original source declaration"));
        if (!header.typeParameters().isEmpty()) {
            // The vocabulary names its machine by a class literal, and a raw literal of a generic type is not a
            // Class<? extends Machine<?, ?, ?>>, so nothing generated for such a facade could compile.
            throw new CodegenException(UNRESOLVED, "the machine " + facade + " declares the type parameters <"
                    + String.join(", ", header.typeParameters()) + ">; a @GenerateGraphApi machine cannot be generic",
                    origin);
        }
        String packageName = header.scope().packageName();
        requireAccessible(index, "the machine interface " + facade, header, packageName, origin);

        Map<TypeParameterKey, TypeUse> arguments =
                specialization(index, header, Map.of(), new HashSet<>())
                        .orElseThrow(() -> new CodegenException(UNRESOLVED,
                                facade + " does not extend " + MACHINE, origin));
        TypeUse.Declared entity = declared(arguments.get(new TypeParameterKey(MACHINE, 0)),
                "entity", facade, origin);
        TypeUse.Declared state = declared(arguments.get(new TypeParameterKey(MACHINE, 1)),
                "state", facade, origin);

        if (index.find(entity.name()).isEmpty()) {
            throw new CodegenException(UNRESOLVED,
                    "the entity type " + entity.render() + " of " + facade + " is unresolved", origin);
        }
        TypeHeader stateHeader = index.find(state.name()).orElseThrow(() -> new CodegenException(UNRESOLVED,
                "the state type " + state.render() + " of " + facade + " is unresolved", origin));
        if (stateHeader.kind() != ElementKind.ENUM) {
            throw new CodegenException(UNRESOLVED, "the state type " + stateHeader.canonicalName() + " of "
                    + facade + " is not an enum", origin);
        }
        TypeUse.Declared transition = declared(arguments.get(new TypeParameterKey(MACHINE, 2)),
            "transition", facade, origin);
        TypeHeader transitionHeader = index.find(transition.name()).orElseThrow(() -> new CodegenException(
                UNRESOLVED, "the transition type " + transition.render() + " of " + facade + " is unresolved", origin));
        if (transitionHeader.kind() != ElementKind.ENUM) {
            throw new CodegenException(UNRESOLVED, "the transition type " + transitionHeader.canonicalName() + " of "
                    + facade + " is not an enum", origin);
        }
        if (transitionHeader.canonicalName().equals(stateHeader.canonicalName())) {
            throw new CodegenException(UNRESOLVED, "the machine " + facade + " uses " + stateHeader.canonicalName()
                    + " as both its state type and its transition type; states and transitions require distinct"
                    + " enum types", origin);
        }
        requireAccessible(index, "the state type " + stateHeader.canonicalName() + " of " + facade, stateHeader,
                packageName, origin);
        requireAccessible(index, "the transition type " + transitionHeader.canonicalName() + " of " + facade,
                transitionHeader, packageName, origin);
        index.requireUnobscured(packageName, "the vocabulary generated for the machine " + facade + " in "
                + where(packageName), qualifiedNames(index, List.of(facade, stateHeader.canonicalName(),
                        transitionHeader.canonicalName())), origin);
        return new GraphDescriptor(header.canonicalName(), header.scope().packageName(), entity.render(),
                stateHeader.canonicalName(), stateHeader.constants(), transitionHeader.canonicalName(),
                transitionHeader.constants(), peers(index, header, origin), origin);
    }

    /**
     * Refuses a peer whose watch vocabulary its owner's package could not compile. The vocabulary of an ordered
     * pair is generated in the owner's package and names the peer machine and the peer's state enum, so both must
     * be accessible there, and no type of that package or of {@code java.lang} may be named like the first segment
     * of a qualified name it writes. A declared peer that is not one of {@code graphs} is left to the emitter, which
     * reports it as {@code SG608} too.
     *
     * @throws CodegenException {@code SG608} anchored at the owner, naming the declaration it cannot access;
     *     {@code SG603} naming a type of the owner's package or of {@code java.lang} that obscures a package the
     *     vocabulary names
     */
    static void requireWatchablePeers(DeclarationIndex index, List<GraphDescriptor> graphs) {
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(graphs, "graphs");
        for (GraphDescriptor owner : graphs) {
            for (String peer : owner.peers()) {
                Optional<GraphDescriptor> watched = graphs.stream()
                        .filter(graph -> graph.facade().equals(peer))
                        .findFirst();
                if (watched.isEmpty()) {
                    continue;
                }
                for (String named : List.of(watched.get().facade(), watched.get().stateType())) {
                    Optional<String> reason = index.find(named)
                            .flatMap(type -> index.inaccessibility(type, owner.packageName()));
                    if (reason.isPresent()) {
                        throw new CodegenException(UNUSABLE_PEER, "the peer machine " + peer + " declared by "
                                + owner.facade() + " cannot be watched from " + where(owner.packageName())
                                + ", where its watch vocabulary is generated: " + reason.get(), owner.origin());
                    }
                }
                index.requireUnobscured(owner.packageName(), "the watch vocabulary generated for the machine "
                        + owner.facade() + " over " + peer + " in " + where(owner.packageName()),
                        qualifiedNames(index, List.of(owner.facade(), peer, owner.stateType(),
                                owner.transitionType(), watched.get().stateType())), owner.origin());
            }
        }
    }

    /**
     * The qualified names a vocabulary writes: the packages of the StateGraph and JDK types it names, then every
     * one of {@code declarations} that belongs to a named package. A declaration of the unnamed package is written
     * by its simple name, which names the declaration itself rather than a package.
     */
    private static List<String> qualifiedNames(DeclarationIndex index, List<String> declarations) {
        List<String> names = new ArrayList<>(VOCABULARY_PACKAGES);
        for (String declaration : declarations) {
            index.find(declaration)
                    .filter(type -> !index.packageOf(type).isEmpty())
                    .ifPresent(type -> names.add(type.canonicalName()));
        }
        return names;
    }

    /** Refuses {@code type}, which the vocabulary generated in {@code packageName} names, when it cannot access it. */
    private static void requireAccessible(DeclarationIndex index, String described, TypeHeader type,
            String packageName, SourceLocation origin) {
        Optional<String> reason = index.inaccessibility(type, packageName);
        if (reason.isPresent()) {
            throw new CodegenException(UNRESOLVED, described + " is not accessible to the vocabulary generated in "
                    + where(packageName) + ": " + reason.get(), origin);
        }
    }

    private static String where(String packageName) {
        return packageName.isEmpty() ? "the unnamed package" : "package " + packageName;
    }

    /**
     * Walks the supertypes of {@code header} under {@code environment} until {@code Machine} is reached and
     * returns the arguments bound to its own parameters. Supertypes that cannot be resolved are skipped: only
     * the chain that actually reaches {@code Machine} has to be resolvable.
     */
    private static Optional<Map<TypeParameterKey, TypeUse>> specialization(DeclarationIndex index,
            TypeHeader header, Map<TypeParameterKey, TypeUse> environment, Set<String> visited) {
        if (!visited.add(header.canonicalName())) {
            return Optional.empty();
        }
        for (TypeUse supertype : header.supertypes()) {
            if (!(supertype instanceof TypeUse.Declared declared)) {
                continue;
            }
            Optional<TypeHeader> resolved = index.resolveInHeader(header, declared.name());
            if (resolved.isEmpty()) {
                continue;
            }
            TypeHeader superHeader = resolved.get();
            Map<TypeParameterKey, TypeUse> bound = bind(index, header, superHeader, declared, environment);
            if (MACHINE.equals(superHeader.canonicalName())) {
                return Optional.of(bound);
            }
            Optional<Map<TypeParameterKey, TypeUse>> found =
                    specialization(index, superHeader, bound, visited);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /** Binds the parameters a supertype declares to the arguments its use spells, under {@code environment}. */
    private static Map<TypeParameterKey, TypeUse> bind(DeclarationIndex index, TypeHeader header,
            TypeHeader superHeader, TypeUse.Declared supertype,
            Map<TypeParameterKey, TypeUse> environment) {
        Map<TypeParameterKey, TypeUse> bound = new LinkedHashMap<>();
        List<TypeUse> arguments = supertype.arguments();
        int declared = Math.min(superHeader.typeParameters().size(), arguments.size());
        for (int position = 0; position < declared; position++) {
            bound.put(superHeader.parameterKey(position),
                    index.substitute(index.canonicalizeInHeader(header, arguments.get(position)), environment));
        }
        return bound;
    }

    private static List<String> peers(DeclarationIndex index, TypeHeader header, SourceLocation origin) {
        List<String> peers = new ArrayList<>();
        for (TypeHeader.Marker marker : header.markers()) {
            if (!index.marksInHeader(header, marker, GRAPH_PEERS)) {
                continue;
            }
            for (String literal : marker.classLiterals()) {
                peers.add(index.resolveInHeader(header, literal)
                        .orElseThrow(() -> new CodegenException(UNRESOLVED, "the peer machine " + literal
                                + " declared by " + header.canonicalName() + " is unresolved",
                                marker.origin().orElse(origin)))
                        .canonicalName());
            }
        }
        return peers;
    }

    private static TypeUse.Declared declared(TypeUse use, String role, String facade, SourceLocation origin) {
        if (use instanceof TypeUse.Declared value) {
            return value;
        }
        throw new CodegenException(UNRESOLVED, "the " + role + " type of " + facade + " is not a declared type"
                + (use == null ? "" : ": " + use.render()), origin);
    }
}
