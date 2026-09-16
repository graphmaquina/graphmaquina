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
 * @param <T> transition identifier enum of the machine
 */
public final class OutcomeAssert<E, S extends Enum<S>, T extends Enum<T>>
        extends BaseOutcomeAssert<E, S, T, OutcomeAssert<E, S, T>> {

    private OutcomeAssert(Outcome<E, S, T> actual) {
        super("outcome", actual);
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
        return new AppliedOutcomeAssert<>((Outcome.Applied<E, S, T>) narrow("Applied"));
    }

    public FailedOutcomeAssert<E, S, T> isFailed() {
        return new FailedOutcomeAssert<>((Outcome.Failed<E, S, T>) narrow("Failed"));
    }

    public RejectedOutcomeAssert<E, S, T> isRejected() {
        return new RejectedOutcomeAssert<>((Outcome.Rejected<E, S, T>) narrow("Rejected"));
    }

    public IgnoredOutcomeAssert<E, S, T> isIgnored() {
        return new IgnoredOutcomeAssert<>((Outcome.Ignored<E, S, T>) narrow("Ignored"));
    }

    private Outcome<E, S, T> narrow(String kind) {
        Outcome<E, S, T> outcome = Checks.subject(context, "kind", kind, actual);
        Checks.equal(context, "kind", kind, outcome.getClass().getSimpleName());
        return outcome;
    }
}
