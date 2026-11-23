package io.github.graphmaquina.api;

/**
 * When a machine takes its automatic edges, declared once per graph and read through
 * {@link GraphDefinition#automaticChain()}. A graph that declares nothing is {@link #AFTER_COMMIT}.
 */
public enum AutomaticChain {

    /**
     * The default. After the commit of any root call, each entity that has a hop or a sub-machine start in that
     * commit runs its automatic chain.
     */
    AFTER_COMMIT,

    /**
     * The machine's automatic edges are taken only by {@code advance(entity)}: the attempt it makes, then the chain of
     * that same entity that follows the attempt's commit, over every automatic edge. The commit of any other call
     * runs no chain for an entity of that machine: an operation, an execute, a choice, a reevaluate, a watch
     * reaction, a cascade, the start or the exit of a sub-machine, and an attempt or a chain of another entity. A
     * nested call that moves the advanced entity stops its chain.
     */
    ON_ADVANCE
}
