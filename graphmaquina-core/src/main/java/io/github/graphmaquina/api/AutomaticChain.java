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
     * The machine takes its automatic edges on its own only on {@code advance(entity)}: the attempt it makes, then the
     * chain of that same entity that follows the attempt's commit, over every automatic edge. The commit of any other
     * call runs no chain for an entity of that machine: an operation, an execute, a choice, a reevaluate, a watch
     * reaction, a cascade, the start or the exit of a sub-machine, and an attempt or a chain of another entity. Such a
     * call may still name an automatic edge's identifier, as an execute of it, a choice's candidate, a watch rule or a
     * cascade does: it runs that edge, and its commit chains no entity of that machine, even when it fails by a route
     * that continues, while the entities of an {@link #AFTER_COMMIT} machine that it moved chain as usual. A nested
     * call that moves the advanced entity stops its chain, unless that nested call is itself an advance of the entity,
     * such as a hook's call to advance, which chains it in its own outcome.
     */
    ON_ADVANCE
}
