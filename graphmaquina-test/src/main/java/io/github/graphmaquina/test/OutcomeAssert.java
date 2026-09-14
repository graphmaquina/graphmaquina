package io.github.graphmaquina.test;

import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.test.internal.Checks;

/**
 * Chainable, typed assertion over an {@link Outcome} of unknown variant. The four narrowing methods
 * assert the variant and hand back the view that exposes what only that variant carries; a subject of
 * the wrong variant — or no subject at all — fails there, so a narrowed view always has one.
 *
 * @param <E> entity type of the machine
 * @param <S> state enum of the machine
 */
public final class OutcomeAssert<E, S extends Enum<S>> extends BaseOutcomeAssert<E, S, OutcomeAssert<E, S>> {

    private OutcomeAssert(Outcome<E, S> actual) {
        super("outcome", actual);
    }

    OutcomeAssert(String context, Outcome<E, S> actual) {
        super(context, actual);
    }

    public static <E, S extends Enum<S>> OutcomeAssert<E, S> assertThat(Outcome<E, S> actual) {
        return new OutcomeAssert<>(actual);
    }

    @Override
    protected OutcomeAssert<E, S> self() {
        return this;
    }

    public AppliedOutcomeAssert<E, S> isApplied() {
        return new AppliedOutcomeAssert<>((Outcome.Applied<E, S>) narrow("Applied"));
    }

    public FailedOutcomeAssert<E, S> isFailed() {
        return new FailedOutcomeAssert<>((Outcome.Failed<E, S>) narrow("Failed"));
    }

    public RejectedOutcomeAssert<E, S> isRejected() {
        return new RejectedOutcomeAssert<>((Outcome.Rejected<E, S>) narrow("Rejected"));
    }

    public IgnoredOutcomeAssert<E, S> isIgnored() {
        return new IgnoredOutcomeAssert<>((Outcome.Ignored<E, S>) narrow("Ignored"));
    }

    private Outcome<E, S> narrow(String kind) {
        Outcome<E, S> outcome = Checks.subject(context, "kind", kind, actual);
        Checks.equal(context, "kind", kind, outcome.getClass().getSimpleName());
        return outcome;
    }
}
