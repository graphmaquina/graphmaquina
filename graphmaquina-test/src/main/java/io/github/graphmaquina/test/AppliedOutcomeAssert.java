package io.github.graphmaquina.test;

import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.test.internal.Checks;
import lombok.NonNull;

/**
 * Chainable, typed assertion over an {@link Outcome.Applied}, obtained from {@link
 * OutcomeAssert#isApplied()}. An applied outcome ran the edge it was asked for, so its recorded destination
 * is also the destination of that edge. Returned by a root call, it was committed; returned by a cascade, it
 * is written only if its root commits.
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

    /**
     * The destination of the edge, committed when a root call returned this outcome and written only if its root
     * commits when a cascade did. It says nothing about where the entity is read to be now.
     */
    public AppliedOutcomeAssert<E, S, T> movedTo(@NonNull S expected) {
        Checks.equal(context, "moved to", expected, applied.to());
        return this;
    }
}
