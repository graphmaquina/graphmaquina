package io.github.graphmaquina.api;

/**
 * Where a call was when it failed, seen from the call itself: each phase says what has run, and {@link StatesWritten} says what has been written. A step a cascade runs does not change the phase of its call, which stays {@link #ACTION} while the
 * cascade runs. The names are stable identifiers, safe to log or to use as a metric tag.
 */
public enum CallPhase {

    /**
     * Reading the entity's identity and state and selecting the edge; in a reevaluation, also evaluating the watch.
     * No guard, action or setter of the call has run.
     */
    RESOLVE,

    /** The guards of the call's own edge. No action of the call has run. */
    GUARD,

    /**
     * From the moment the call's guards passed until its step ended: its action, the cascades the action ran with
     * their guards and actions, and the failure route. The journal says which actions ran.
     */
    ACTION,

    /** The check that every entity the call moves is still in the state it was read in, then the setters. */
    COMMIT,

    /** The after-commit hooks. The states are written. */
    HOOK,

    /** The watch reactions of the commit. Only an Error leaves this phase: an exception becomes a {@link Reaction.Aborted}. */
    REACTIONS
}
