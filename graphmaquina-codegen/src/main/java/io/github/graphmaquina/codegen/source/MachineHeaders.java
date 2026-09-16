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
 * Specializes one machine interface to the {@code Machine<E, S, T>} it ultimately extends. The supertype chain
 * is walked by declaration identity, substituting each type argument into the parameters the supertype declares,
 * so a generic base contributes its specialization whether it is an original source or a classpath binary.
 */
public final class MachineHeaders {

    private static final String MACHINE = "io.github.graphmaquina.api.Machine";
    private static final String GRAPH_PEERS = "io.github.graphmaquina.annotation.GraphPeers";
    private static final String UNRESOLVED = "SG601";

    private MachineHeaders() {
    }

    /**
     * Resolves the entity, the state enum, the transition enum, the constants of both and the declared peers
     * of {@code facade}.
     */
    public static GraphDescriptor resolve(DeclarationIndex index, String facade) {
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(facade, "facade");
        TypeHeader header = index.find(facade).orElseThrow(() -> new CodegenException(UNRESOLVED,
                "the machine interface " + facade + " is not declared"));
        SourceLocation origin = header.origin().orElseThrow(() -> new CodegenException(UNRESOLVED,
                "the machine interface " + facade + " is not an original source declaration"));

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
        TypeHeader transitionHeader = index.find(transition.name()).orElseThrow(() ->
            new CodegenException(UNRESOLVED, "unresolved transition enum " + transition.render(), origin));
        if (transitionHeader.kind() != ElementKind.ENUM) {
            throw new CodegenException(UNRESOLVED, "the transition type " + transitionHeader.canonicalName() + " of "
                    + facade + " is not an enum", origin);
        }
        if (transitionHeader.canonicalName().equals(stateHeader.canonicalName())) {
            throw new CodegenException(UNRESOLVED, "states and transitions require distinct enum types", origin);
        }
        return new GraphDescriptor(header.canonicalName(), header.scope().packageName(), entity.render(),
                stateHeader.canonicalName(), stateHeader.constants(), transitionHeader.canonicalName(),
                transitionHeader.constants(), peers(index, header, origin), origin);
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
            Optional<TypeHeader> resolved = index.resolve(header, declared.name());
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
                    index.substitute(index.canonicalize(header, arguments.get(position)), environment));
        }
        return bound;
    }

    private static List<String> peers(DeclarationIndex index, TypeHeader header, SourceLocation origin) {
        List<String> peers = new ArrayList<>();
        for (TypeHeader.Marker marker : header.markers()) {
            if (!index.marks(header, marker, GRAPH_PEERS)) {
                continue;
            }
            for (String literal : marker.classLiterals()) {
                peers.add(index.resolve(header, literal)
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
