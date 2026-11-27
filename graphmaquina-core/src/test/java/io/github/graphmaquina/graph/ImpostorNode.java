package io.github.graphmaquina.graph;

import lombok.RequiredArgsConstructor;

/**
 * A user object that claims to equal the {@code String} node of the same name and hashes like it, so a graph whose
 * nodes are all strings accepts it as an edge endpoint and then stores it. Every call to its {@code equals} and
 * {@code hashCode} is written to a shared log.
 */
@RequiredArgsConstructor
final class ImpostorNode {

    private final String name;
    private final String tag;
    private final StringBuilder log;

    @Override
    public boolean equals(Object other) {
        log.append("e(").append(tag).append(',').append(other).append(')');
        return name.equals(other) || other instanceof ImpostorNode impostor && impostor.name.equals(name);
    }

    @Override
    public int hashCode() {
        log.append("h(").append(tag).append(')');
        return name.hashCode();
    }

    @Override
    public String toString() {
        return tag;
    }
}
