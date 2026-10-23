package io.github.graphmaquina.codegen.source;

import io.github.graphmaquina.codegen.CodegenException;
import io.github.graphmaquina.codegen.model.GraphDescriptor;
import io.github.graphmaquina.codegen.model.SourceLocation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
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
 * interface, and refused like one with {@code SG601} when it does not extend {@code Machine}; the annotation processor
 * is what refuses a class facade that does, with {@code SG502}.
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
     * types generated in that package are checked by the emitters. {@code facade} names the declaration
     * {@link DeclarationIndex#find(String)} finds by it.
     */
    public static GraphDescriptor resolve(DeclarationIndex index, String facade) {
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(facade, "facade");
        return resolveDeclaration(index, index.find(facade).orElseThrow(() -> new CodegenException(UNRESOLVED,
                "the machine interface " + facade + " is not declared"))).graph();
    }

    /**
     * {@link #resolve(DeclarationIndex, String)} for the declaration {@code header} itself, which a type of the other
     * package of its canonical name, named or unnamed, cannot stand in for. The entity and the enums are the types the
     * type arguments of {@code Machine} name where they are spelled, as the header spelling each resolves it, and the
     * peers the types the class literals of {@code @GraphPeers} name; a name no header resolves is unresolved, and
     * a peer named twice is refused with {@code SG608}.
     */
    static Resolved resolveDeclaration(DeclarationIndex index, TypeHeader header) {
        String facade = header.canonicalName();
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

        Binding binding = specialization(index, header, new Binding(Map.of(), Map.of()),
                        Collections.newSetFromMap(new IdentityHashMap<>()))
                .orElseThrow(() -> new CodegenException(UNRESOLVED, facade + " does not extend " + MACHINE, origin));
        Map<TypeParameterKey, TypeUse> arguments = binding.arguments();
        TypeUse.Declared entity = declared(arguments.get(new TypeParameterKey(MACHINE, 0)),
                "entity", facade, origin);
        TypeUse.Declared state = declared(arguments.get(new TypeParameterKey(MACHINE, 1)),
                "state", facade, origin);

        if (binding.type(new TypeParameterKey(MACHINE, 0)).isEmpty()) {
            throw new CodegenException(UNRESOLVED,
                    "the entity type " + entity.render() + " of " + facade + " is unresolved", origin);
        }
        TypeHeader stateHeader = enumType(binding, 1, "state", state, facade, origin);
        TypeUse.Declared transition = declared(arguments.get(new TypeParameterKey(MACHINE, 2)),
            "transition", facade, origin);
        TypeHeader transitionHeader = enumType(binding, 2, "transition", transition, facade, origin);
        if (transitionHeader == stateHeader) {
            throw new CodegenException(UNRESOLVED, "the machine " + facade + " uses " + stateHeader.canonicalName()
                    + " as both its state type and its transition type; states and transitions require distinct"
                    + " enum types", origin);
        }
        requireAccessible(index, "the state type " + stateHeader.canonicalName() + " of " + facade, stateHeader,
                packageName, origin);
        requireAccessible(index, "the transition type " + transitionHeader.canonicalName() + " of " + facade,
                transitionHeader, packageName, origin);
        index.requireUnobscured(packageName, "the vocabulary generated for the machine " + facade + " in "
                + where(packageName), qualifiedNames(index, List.of(header, stateHeader, transitionHeader)), origin);
        List<TypeHeader> peers = peers(index, header, origin);
        return new Resolved(new GraphDescriptor(header.canonicalName(), header.scope().packageName(), entity.render(),
                stateHeader.canonicalName(), stateHeader.constants(), transitionHeader.canonicalName(),
                transitionHeader.constants(), peers.stream().map(TypeHeader::canonicalName).toList(), origin),
                header, stateHeader, transitionHeader, peers);
    }

    /**
     * Refuses a peer whose pair vocabulary its owner's package could not compile. The vocabulary of an ordered
     * pair is generated in the owner's package and names the peer machine and the peer's state enum, so both must
     * be accessible there, and no type of that package or of {@code java.lang} may be named like the first segment
     * of a qualified name it writes. A peer is the type its class literal names. A declared peer that is none of
     * {@code machines} is left to the emitter, which reports it as {@code SG608} too, unless one of them shares its
     * canonical name: the emitter, which matches peers by name, would take that machine for it, so it is refused
     * here.
     *
     * @throws CodegenException {@code SG608} anchored at the owner, naming the declaration it cannot access or the
     *     peer that no machine of this compilation is though one shares its name; {@code SG603} naming a type of the
     *     owner's package or of {@code java.lang} that obscures a package the vocabulary names
     */
    static void requireWatchablePeers(DeclarationIndex index, List<Resolved> machines) {
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(machines, "machines");
        for (Resolved owner : machines) {
            GraphDescriptor graph = owner.graph();
            for (TypeHeader peerType : owner.peers()) {
                String peer = peerType.canonicalName();
                Optional<Resolved> watched = machines.stream()
                        .filter((Resolved machine) -> machine.facade() == peerType)
                        .findFirst();
                if (watched.isEmpty()) {
                    if (machines.stream().anyMatch((Resolved machine) -> machine.graph().facade().equals(peer))) {
                        throw new CodegenException(UNUSABLE_PEER, "the peer machine " + peer + " declared by "
                                + graph.facade() + " is not a @GenerateGraphApi machine of this compilation",
                                graph.origin());
                    }
                    continue;
                }
                Resolved peerMachine = watched.get();
                for (TypeHeader named : List.of(peerMachine.facade(), peerMachine.state())) {
                    Optional<String> reason = index.inaccessibility(named, graph.packageName());
                    if (reason.isPresent()) {
                        throw new CodegenException(UNUSABLE_PEER, "the peer machine " + peer + " declared by "
                                + graph.facade() + " cannot be named from " + where(graph.packageName())
                                + ", where its pair vocabulary is generated: " + reason.get(), graph.origin());
                    }
                }
                index.requireUnobscured(graph.packageName(), "the pair vocabulary generated for the machine "
                        + graph.facade() + " over " + peer + " in " + where(graph.packageName()),
                        qualifiedNames(index, List.of(owner.facade(), peerMachine.facade(), owner.state(),
                                owner.transition(), peerMachine.state())), graph.origin());
            }
        }
    }

    /**
     * The qualified names a vocabulary writes: the packages of the StateGraph and JDK types it names, then the
     * canonical name of every one of {@code declarations} that belongs to a named package. A declaration of the
     * unnamed package is written by its canonical name too, whose first segment names its top-level type there
     * rather than a package.
     */
    private static List<String> qualifiedNames(DeclarationIndex index, List<TypeHeader> declarations) {
        List<String> names = new ArrayList<>(VOCABULARY_PACKAGES);
        for (TypeHeader declaration : declarations) {
            if (!index.packageOf(declaration).isEmpty()) {
                names.add(declaration.canonicalName());
            }
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
     * Walks the supertypes of {@code header} under {@code binding} until {@code Machine} is reached and returns
     * what is bound to its own parameters. Supertypes that cannot be resolved are skipped: only the chain that
     * actually reaches {@code Machine} has to be resolvable. {@code visited} holds the types walked so far by
     * identity, since a type of a named package and a type of the unnamed package can share a canonical name.
     */
    private static Optional<Binding> specialization(DeclarationIndex index, TypeHeader header, Binding binding,
            Set<TypeHeader> visited) {
        if (!visited.add(header)) {
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
            Binding bound = bind(index, header, superHeader, declared, binding);
            if (MACHINE.equals(superHeader.canonicalName())) {
                return Optional.of(bound);
            }
            Optional<Binding> found = specialization(index, superHeader, bound, visited);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /**
     * Binds the parameters a supertype declares to the arguments its use spells, under {@code binding}, and each of
     * them to the type its argument names: the type the header of {@code header} resolves a spelled name to, or the
     * type bound to the type variable it spells.
     */
    private static Binding bind(DeclarationIndex index, TypeHeader header, TypeHeader superHeader,
            TypeUse.Declared supertype, Binding binding) {
        Map<TypeParameterKey, TypeUse> bound = new LinkedHashMap<>();
        Map<TypeParameterKey, TypeHeader> types = new LinkedHashMap<>();
        List<TypeUse> arguments = supertype.arguments();
        int declared = Math.min(superHeader.typeParameters().size(), arguments.size());
        for (int position = 0; position < declared; position++) {
            TypeParameterKey parameter = superHeader.parameterKey(position);
            TypeUse argument = arguments.get(position);
            bound.put(parameter,
                    index.substitute(index.canonicalizeInHeader(header, argument), binding.arguments()));
            Optional<TypeHeader> type = argument instanceof TypeUse.Declared spelled
                    ? index.resolveInHeader(header, spelled.name())
                    : argument instanceof TypeUse.Variable variable
                            ? Optional.ofNullable(binding.types().get(variable.key()))
                            : Optional.empty();
            type.ifPresent((TypeHeader value) -> types.put(parameter, value));
        }
        return new Binding(bound, types);
    }

    /**
     * The types the class literals of the {@code @GraphPeers} of {@code header} name, in declaration order. A type
     * named twice, which is one declaration whatever the spelling of its literals, would generate the vocabulary of
     * one pair twice, so it is refused with the code the annotation processor reports it with.
     *
     * @throws CodegenException {@code SG601} naming a literal no header resolves; {@code SG608} naming a peer
     *     declared twice
     */
    private static List<TypeHeader> peers(DeclarationIndex index, TypeHeader header, SourceLocation origin) {
        List<TypeHeader> peers = new ArrayList<>();
        for (TypeHeader.Marker marker : header.markers()) {
            if (!index.marksInHeader(header, marker, GRAPH_PEERS)) {
                continue;
            }
            for (String literal : marker.classLiterals()) {
                TypeHeader peer = index.resolveInHeader(header, literal)
                        .orElseThrow(() -> new CodegenException(UNRESOLVED, "the peer machine " + literal
                                + " declared by " + header.canonicalName() + " is unresolved",
                                marker.origin().orElse(origin)));
                if (peers.stream().anyMatch((TypeHeader declared) -> declared == peer)) {
                    throw new CodegenException(UNUSABLE_PEER, peer.canonicalName() + " is declared twice as a peer of "
                            + header.canonicalName(), marker.origin().orElse(origin));
                }
                peers.add(peer);
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

    /**
     * The enum the type argument of {@code Machine} at {@code position} names, the {@code role} type of
     * {@code facade}, which that argument spells as {@code spelled}.
     *
     * @throws CodegenException {@code SG601} if no header resolves the argument, or if the type it names is no enum
     */
    private static TypeHeader enumType(Binding binding, int position, String role, TypeUse.Declared spelled,
            String facade, SourceLocation origin) {
        TypeHeader type = binding.type(new TypeParameterKey(MACHINE, position))
                .orElseThrow(() -> new CodegenException(UNRESOLVED,
                        "the " + role + " type " + spelled.render() + " of " + facade + " is unresolved", origin));
        if (type.kind() != ElementKind.ENUM) {
            throw new CodegenException(UNRESOLVED, "the " + role + " type " + type.canonicalName() + " of "
                    + facade + " is not an enum", origin);
        }
        return type;
    }

    /**
     * What one supertype's use binds to the type parameters that supertype declares: the canonicalized argument of
     * each, and the type each argument names, as the header that spells it resolves it, for every argument whose name
     * that header resolves.
     *
     * @param arguments the argument bound to each parameter, canonicalized and specialized
     * @param types the type the argument bound to each parameter names, for the arguments that name one
     */
    private record Binding(Map<TypeParameterKey, TypeUse> arguments, Map<TypeParameterKey, TypeHeader> types) {

        /** The type the argument bound to {@code parameter} names, empty when no header resolves it. */
        Optional<TypeHeader> type(TypeParameterKey parameter) {
            return Optional.ofNullable(types.get(parameter));
        }
    }

    /**
     * One machine as {@link #resolveDeclaration} resolved it, with the declarations it was resolved with, which a type
     * of the other package of the same canonical name, named or unnamed, cannot stand in for.
     *
     * @param graph the descriptor of the machine
     * @param facade the facade's own declaration
     * @param state the state enum
     * @param transition the transition enum
     * @param peers the types the class literals of its {@code @GraphPeers} name, in declaration order
     */
    record Resolved(GraphDescriptor graph, TypeHeader facade, TypeHeader state, TypeHeader transition,
            List<TypeHeader> peers) {

        /** Copies the list, so a resolved machine never reflects a later change of the list it was built from. */
        Resolved {
            Objects.requireNonNull(graph, "graph");
            Objects.requireNonNull(facade, "facade");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(transition, "transition");
            peers = List.copyOf(peers);
        }
    }
}
