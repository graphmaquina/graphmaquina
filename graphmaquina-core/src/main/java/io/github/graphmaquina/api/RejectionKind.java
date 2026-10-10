package io.github.graphmaquina.api;

/**
 * Why a request was rejected: no edge leaves the current state with that identifier, a guard said no, the machine is
 * bound as a sub-machine and its parent does not hold it, or the identifier is an exit of the sub-machine bound to the
 * current state, which no request may ask for from that state.
 */
public enum RejectionKind {

    /** No edge leaves the current state with the requested identifier. */
    NO_TRANSITION,

    /** A guard of the selected edge said no; the rejection names that guard. */
    GUARD,

    /**
     * The entity has a parent under some sub-machine binding of its machine, and no such binding is active: no parent
     * holds it, either because the parent is neither in the bound state nor leaving it, or because the parent's
     * childOf answers another child or none. Checked before the edge is looked up, so no guard ran.
     */
    DETACHED,

    /**
     * The identifier is mapped as an exit of the sub-machine bound to the current state: from that state only the end
     * of the sub-machine runs it, so no request may ask for it there; from another state it is not reserved. Checked
     * once the edge is selected, before its guards.
     */
    RESERVED_EXIT
}
