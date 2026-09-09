package io.stategraph.codegen.model;

import java.util.List;
import java.util.Objects;

/**
 * One resolved {@code @GenerateGraphApi} machine: its facade, the package that facade is declared in, its
 * entity, its state enum, that enum's states and the machine's declared peers.
 *
 * <p>{@code packageName} is the package of the facade exactly as its declaration spells it, empty for the
 * unnamed package. It is carried here because a canonical name alone cannot be split into a package and a
 * nested type name without guessing, and the generated vocabulary of the machine must land in that package.</p>
 */
public record GraphDescriptor(String facade, String packageName, String entityType, String stateType,
        List<String> states, List<String> peers, SourceLocation origin) {

    public GraphDescriptor {
        Objects.requireNonNull(facade, "facade");
        Objects.requireNonNull(packageName, "packageName");
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(stateType, "stateType");
        Objects.requireNonNull(states, "states");
        Objects.requireNonNull(peers, "peers");
        Objects.requireNonNull(origin, "origin");
        states = List.copyOf(states);
        peers = List.copyOf(peers);
    }
}
