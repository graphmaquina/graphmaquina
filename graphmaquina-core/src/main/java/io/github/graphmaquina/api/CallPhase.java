package io.github.graphmaquina.api;

/**
 * Where a call was when it failed, seen from the call itself: each phase says what has run, and {@link StatesWritten}
 * says what has been written. A step a cascade runs does not change the phase of its call, which stays {@link #ACTION}
 * while the cascade runs. The names are stable identifiers, safe to log or to use as a metric tag.
 */
public enum CallPhase {

    /**
     * Reading the entity's identity and state, checking the binding of a sub-machine, and selecting the edge; in a
     * reevaluation, also evaluating the watch. An advance reads the state and its automatic edges, then enters this
     * phase again for each one it tries. No action or setter of the call has run, nor any guard but those of the
     * edges an advance tried and refused before.
     */
    RESOLVE,

    /** The guards of the call's own edge, or of the edge an advance tries. No action of the call has run. */
    GUARD,

    /**
     * From the moment the call's guards passed until its step ended: its action, the cascades the action ran with
     * their guards and actions, the failure route, the start of each sub-machine whose state an edge enters, and the
     * exit of each one that ends. The journal says which actions ran.
     */
    ACTION,

    /**
     * Grouping the call's log by entity, the check that every entity the call moves is still in the state it was
     * read in, then the setters, until the last one returns.
     */
    COMMIT,

    /**
     * From the moment the last setter of the commit returned: the after-commit hooks, and before them the DEBUG lines
     * of the hops and starts the call committed. The states are written.
     */
    HOOK,

    /**
     * The watch reactions of the commit. Only an Error leaves this phase: any other throwable becomes a
     * {@link Reaction.Aborted}.
     */
    REACTIONS,

    /**
     * The automatic chains of the entities the commit moved, once its watch reactions ran: each attempt is a call of
     * its own. Only an Error leaves this phase: any other throwable becomes an {@link AutomaticStep.Aborted}.
     */
    AUTOMATIC_STEPS
}
