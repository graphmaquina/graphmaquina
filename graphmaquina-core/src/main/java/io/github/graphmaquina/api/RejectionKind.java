package io.github.graphmaquina.api;

/**
 * Why a request was rejected: no edge leaves the current state with that identifier, a guard said no, the machine is
 * bound as a sub-machine and its parent is not in the bound state, or the identifier is an exit of the sub-machine
 * bound to the current state, which no request may ask for from that state.
 */
public enum RejectionKind {

    /** No edge leaves the current state with the requested identifier. */
    NO_TRANSITION,

    /** A guard of the selected edge said no; the rejection names that guard. */
    GUARD,

    /**
     * The entity has a parent under some sub-machine binding of its machine, and no such binding is active: the parent
     * is neither in the bound state nor leaving it. Checked before the edge is looked up, so no guard ran.
     */
    DETACHED,

    /**
     * The identifier is mapped as an exit of the sub-machine bound to the current state, so no request may ask for it
     * from that state; from another state it is not reserved. Checked once the edge is selected, before its guards.
     */
    RESERVED_EXIT
}
