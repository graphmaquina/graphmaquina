package io.github.graphmaquina.api;

/**
 * Why a call that requests no identifier ran no edge: a reevaluation whose state declares no watch, or none of whose
 * watch rules held; an advance whose state has no automatic edge, or none of whose automatic edges was admitted; a
 * choice none of whose candidates was admitted.
 */
public enum IgnoreReason {

    /** A reevaluation: the state declares no watch. */
    NO_WATCH,

    /** A reevaluation: no rule of the state's watch held. */
    NO_RULE_MATCHED,

    /** An advance: the state has no automatic edge. */
    NO_AUTOMATIC_TRANSITION,

    /**
     * An advance or a choice: every candidate it tried was refused. The candidates of an advance are the automatic
     * edges of the state, each refused by a guard or because the entity is a sub-machine whose parent does not hold it;
     * those of a choice are the caller's identifiers, each refused for any {@link RejectionKind}, such as a
     * {@link RejectionKind#NO_TRANSITION} when no edge keys it from the state.
     */
    NO_CANDIDATE_ADMITTED
}
