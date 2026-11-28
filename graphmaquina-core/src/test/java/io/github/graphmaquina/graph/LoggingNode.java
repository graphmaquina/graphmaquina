package io.github.graphmaquina.graph;

import lombok.RequiredArgsConstructor;

/**
 * A graph node whose {@code equals} and {@code hashCode} are user code: every call is written to a shared log, with
 * the tag of the instance it ran on and of its argument, so a test can pin how many calls a graph operation makes
 * and in what order. Equality is by id; two ids share each hash ({@code id / 2}), so lookups also compare distinct
 * nodes. No bucket of a hash table ever holds eight of these, so no table turns a bucket into a tree, whose shape
 * would depend on identity hash codes and make the log differ from run to run.
 */
@RequiredArgsConstructor
final class LoggingNode {

    private final int id;
    private final String tag;
    private final StringBuilder log;

    @Override
    public boolean equals(Object other) {
        log.append("e(").append(tag).append(',').append(other).append(')');
        return other instanceof LoggingNode node && node.id == id;
    }

    @Override
    public int hashCode() {
        log.append("h(").append(tag).append(')');
        return id / 2;
    }

    @Override
    public String toString() {
        return tag;
    }
}
