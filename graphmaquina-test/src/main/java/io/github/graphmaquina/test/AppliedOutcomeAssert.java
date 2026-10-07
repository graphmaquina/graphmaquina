package io.github.graphmaquina.test;

import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.test.internal.Checks;
import java.util.Objects;

/**
 * Chainable, typed assertion over an {@link Outcome.Applied}, obtained from {@link
 * OutcomeAssert#isApplied()}. An applied outcome committed the edge it was asked for, so its recorded
 * destination is also the destination of that edge.
 *
 * @param <E> entity type of the machine
 * @param <S> state enum of the machine
 * @param <T> transition identifier enum of the machine
 */
public final class AppliedOutcomeAssert<E, S extends Enum<S>, T extends Enum<T>>
        extends BaseOutcomeAssert<E, S, T, AppliedOutcomeAssert<E, S, T>> {

    private final Outcome.Applied<E, S, T> applied;

    AppliedOutcomeAssert(String context, Outcome.Applied<E, S, T> applied) {
        super(context, applied);
        this.applied = applied;
    }

    @Override
    protected AppliedOutcomeAssert<E, S, T> self() {
        return this;
    }

    /** The committed destination, which says nothing about where the entity is read to be now. */
    public AppliedOutcomeAssert<E, S, T> movedTo(S expected) {
        Objects.requireNonNull(expected, "expected");
        Checks.equal(context, "moved to", expected, applied.to());
        return this;
    }
}
