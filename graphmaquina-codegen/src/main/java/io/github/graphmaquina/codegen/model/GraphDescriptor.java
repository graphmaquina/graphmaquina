package io.github.graphmaquina.codegen.model;

import java.util.List;
import java.util.Objects;

/**
 * One resolved {@code @GenerateGraphApi} machine: its facade, the package that facade is declared in, its
 * entity, its state enum and that enum's states, its transition enum and that enum's identifiers, and the
 * machine's declared peers.
 *
 * <p>{@code packageName} is the package of the facade exactly as its declaration spells it, empty for the
 * unnamed package. It is carried here because a canonical name alone cannot be split into a package and a
 * nested type name without guessing, and the generated vocabulary of the machine must land in that package.</p>
 *
 * <p>{@code stateType} and {@code transitionType} are always two distinct concrete enums: the states a machine
 * moves between and the identifiers of the edges it moves along. {@code states} and {@code transitions} keep
 * the declaration order of their enums, which is the order the generated vocabulary and every view depend
 * on.</p>
 */
public record GraphDescriptor(String facade, String packageName, String entityType, String stateType,
        List<String> states, String transitionType, List<String> transitions, List<String> peers,
        SourceLocation origin) {

    public GraphDescriptor {
        Objects.requireNonNull(facade, "facade");
        Objects.requireNonNull(packageName, "packageName");
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(stateType, "stateType");
        Objects.requireNonNull(states, "states");
        Objects.requireNonNull(transitionType, "transitionType");
        Objects.requireNonNull(transitions, "transitions");
        Objects.requireNonNull(peers, "peers");
        Objects.requireNonNull(origin, "origin");
        states = List.copyOf(states);
        transitions = List.copyOf(transitions);
        peers = List.copyOf(peers);
    }
}
