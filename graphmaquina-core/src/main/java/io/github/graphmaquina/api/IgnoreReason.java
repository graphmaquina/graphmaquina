package io.github.graphmaquina.api;

/**
 * Why a call that requests no identifier ran no edge: a reevaluation whose state declares no watch, or none of whose
 * watch rules held; an advance whose state has no automatic edge, or none of whose automatic edges was admitted.
 */
public enum IgnoreReason {

    /** A reevaluation: the state declares no watch. */
    NO_WATCH,

    /** A reevaluation: no rule of the state's watch held. */
    NO_RULE_MATCHED,

    /** An advance: the state has no automatic edge. */
    NO_AUTOMATIC_TRANSITION,

    /**
     * An advance: every automatic edge of the state was refused, each by a guard or because the entity is a
     * sub-machine whose parent does not hold it.
     */
    NO_CANDIDATE_ADMITTED
}
