package io.github.graphmaquina.test;

import io.github.graphmaquina.api.IgnoreReason;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.test.internal.Checks;
import lombok.NonNull;

/**
 * Chainable, typed assertion over an {@link Outcome.Ignored}, obtained from {@link
 * OutcomeAssert#isIgnored()}. A reevaluation or an advance ran no edge, so no action ran and nothing moved:
 * the recorded state is the origin, no identifier was requested, and {@link #hasReason(IgnoreReason)} says
 * whether the state declared no watch at all or no rule of its watch held, for a reevaluation, or whether the
 * state had no automatic edge or none of them was admitted, for an advance, in which case {@link #rejections()} says
 * why each edge was not admitted.
 *
 * @param <E> entity type of the machine
 * @param <S> state enum of the machine
 * @param <T> transition identifier enum of the machine
 */
public final class IgnoredOutcomeAssert<E, S extends Enum<S>, T extends Enum<T>>
        extends BaseOutcomeAssert<E, S, T, IgnoredOutcomeAssert<E, S, T>> {

    private final Outcome.Ignored<E, S, T> ignored;

    IgnoredOutcomeAssert(String context, Outcome.Ignored<E, S, T> ignored) {
        super(context, ignored);
        this.ignored = ignored;
    }

    @Override
    protected IgnoredOutcomeAssert<E, S, T> self() {
        return this;
    }

    public IgnoredOutcomeAssert<E, S, T> hasReason(@NonNull IgnoreReason expected) {
        Checks.equal(context, "reason", expected, ignored.reason());
        return this;
    }

    /**
     * The rejection of each automatic edge the advance tried, in the order it tried them: empty for every reason but
     * {@link IgnoreReason#NO_CANDIDATE_ADMITTED}.
     */
    public RejectionsAssert<E, S, T> rejections() {
        return new RejectionsAssert<>(context + ".rejections", ignored.rejections());
    }
}
