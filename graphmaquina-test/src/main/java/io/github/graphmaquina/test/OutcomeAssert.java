package io.github.graphmaquina.test;

import io.github.graphmaquina.api.Outcome;
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
        return new AppliedOutcomeAssert<>(narrowed("applied"), (Outcome.Applied<E, S, T>) narrow("Applied"));
    }

    public FailedOutcomeAssert<E, S, T> isFailed() {
        return new FailedOutcomeAssert<>(narrowed("failed"), (Outcome.Failed<E, S, T>) narrow("Failed"));
    }

    public RejectedOutcomeAssert<E, S, T> isRejected() {
        return new RejectedOutcomeAssert<>(narrowed("rejected"), (Outcome.Rejected<E, S, T>) narrow("Rejected"));
    }

    public IgnoredOutcomeAssert<E, S, T> isIgnored() {
        return new IgnoredOutcomeAssert<>(narrowed("ignored"), (Outcome.Ignored<E, S, T>) narrow("Ignored"));
    }

    /** A subject handed in directly is named by its variant alone; one from another view keeps that view's name. */
    private String narrowed(String variant) {
        return ROOT.equals(context) ? variant + " outcome" : context + " (" + variant + ")";
    }

    private Outcome<E, S, T> narrow(String kind) {
        Outcome<E, S, T> outcome = Checks.subject(context, "kind", kind, actual);
        Checks.equal(context, "kind", kind, outcome.getClass().getSimpleName());
        return outcome;
    }
}
