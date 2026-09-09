package io.stategraph.codegen.model;

import java.util.List;
import java.util.Objects;

/** One resolved {@code @GenerateGraphApi} machine: its facade, entity, state enum, states and declared peers. */
public record GraphDescriptor(String facade, String entityType, String stateType,
        List<String> states, List<String> peers, SourceLocation origin) {

    public GraphDescriptor {
        Objects.requireNonNull(facade, "facade");
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(stateType, "stateType");
        Objects.requireNonNull(states, "states");
        Objects.requireNonNull(peers, "peers");
        Objects.requireNonNull(origin, "origin");
        states = List.copyOf(states);
        peers = List.copyOf(peers);
    }
}
