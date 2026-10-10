package io.github.graphmaquina.api;

/**
 * Why an {@link Outcome} is what it is, as one flat value: its variant, refined by the {@link RejectionKind} of a
 * rejection and by the {@link IgnoreReason} of a reevaluation that selected nothing. Each code belongs to exactly
 * one {@link OutcomeStatus}. The names are stable identifiers, safe to persist or serialize; a code is added only
 * together with a new variant, rejection kind or ignore reason.
 */
public enum OutcomeCode {

    /** The selected edge ran: {@link Outcome.Applied}. */
    APPLIED(OutcomeStatus.APPLIED),

    /** No edge leaves the origin under the requested identifier: {@link RejectionKind#NO_TRANSITION}. */
    NO_TRANSITION(OutcomeStatus.REJECTED),

    /** A guard of the selected edge rejected it: {@link RejectionKind#GUARD}. */
    GUARD_REJECTED(OutcomeStatus.REJECTED),

    /** The entity is a sub-machine whose parent does not hold it: {@link RejectionKind#DETACHED}. */
    DETACHED(OutcomeStatus.REJECTED),

    /**
     * The identifier is an exit of the sub-machine bound to the origin, which no request may ask for from there:
     * {@link RejectionKind#RESERVED_EXIT}.
     */
    RESERVED_EXIT(OutcomeStatus.REJECTED),

    /** The action failed and its failure route was taken: {@link Outcome.Failed}. */
    FAILURE_ROUTED(OutcomeStatus.FAILED),

    /** The state declares no watch: {@link IgnoreReason#NO_WATCH}. */
    NO_WATCH(OutcomeStatus.IGNORED),

    /** No rule of the state's watch held: {@link IgnoreReason#NO_RULE_MATCHED}. */
    NO_RULE_MATCHED(OutcomeStatus.IGNORED);

    private final OutcomeStatus status;

    OutcomeCode(OutcomeStatus status) {
        this.status = status;
    }

    /** The status this code refines. */
    public OutcomeStatus status() {
        return status;
    }
}
