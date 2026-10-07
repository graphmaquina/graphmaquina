package io.github.graphmaquina.test;

import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.OutcomeStatus;
import io.github.graphmaquina.test.internal.Checks;

/**
 * Chainable, typed assertion over an {@link Outcome} of unknown variant. The four narrowing methods
 * assert the variant and hand back the view that exposes what only that variant carries; a subject of
 * the wrong variant — or no subject at all — fails there, so a narrowed view always has one.
 *
 * <p>A failure names the view it came from. A subject handed in directly reads {@code applied outcome} once
 * narrowed; one that came from a sequence or a reaction keeps its place, as in
 * {@code recorded transitions for K[1] (applied)} or {@code reactions for K[0].outcome (applied)}, and the
 * lists opened from it extend that name.
 *
 * @param <E> entity type of the machine
 * @param <S> state enum of the machine
 * @param <T> transition identifier enum of the machine
 */
public final class OutcomeAssert<E, S extends Enum<S>, T extends Enum<T>>
        extends BaseOutcomeAssert<E, S, T, OutcomeAssert<E, S, T>> {

    private static final String ROOT = "outcome";

    private OutcomeAssert(Outcome<E, S, T> actual) {
        super(ROOT, actual);
    }

    OutcomeAssert(String context, Outcome<E, S, T> actual) {
        super(context, actual);
    }

    public static <E, S extends Enum<S>, T extends Enum<T>> OutcomeAssert<E, S, T> assertThat(
            Outcome<E, S, T> actual) {
        return new OutcomeAssert<>(actual);
    }

    @Override
    protected OutcomeAssert<E, S, T> self() {
        return this;
    }

    public AppliedOutcomeAssert<E, S, T> isApplied() {
        Outcome.Applied<E, S, T> applied = narrow(OutcomeStatus.APPLIED).asApplied().orElseThrow();
        return new AppliedOutcomeAssert<>(narrowed("applied"), applied);
    }

    public FailedOutcomeAssert<E, S, T> isFailed() {
        Outcome.Failed<E, S, T> failed = narrow(OutcomeStatus.FAILED).asFailed().orElseThrow();
        return new FailedOutcomeAssert<>(narrowed("failed"), failed);
    }

    public RejectedOutcomeAssert<E, S, T> isRejected() {
        Outcome.Rejected<E, S, T> rejected = narrow(OutcomeStatus.REJECTED).asRejected().orElseThrow();
        return new RejectedOutcomeAssert<>(narrowed("rejected"), rejected);
    }

    public IgnoredOutcomeAssert<E, S, T> isIgnored() {
        Outcome.Ignored<E, S, T> ignored = narrow(OutcomeStatus.IGNORED).asIgnored().orElseThrow();
        return new IgnoredOutcomeAssert<>(narrowed("ignored"), ignored);
    }

    /** A subject handed in directly is named by its variant alone; one from another view keeps that view's name. */
    private String narrowed(String variant) {
        return ROOT.equals(context) ? variant + " outcome" : context + " (" + variant + ")";
    }

    /** The subject, once it is known to report {@code status}; the narrowing that follows cannot come back empty. */
    private Outcome<E, S, T> narrow(OutcomeStatus status) {
        Outcome<E, S, T> outcome = Checks.subject(context, "status", status, actual);
        Checks.equal(context, "status", status, outcome.status());
        return outcome;
    }
}
