package io.github.graphmaquina.test;

import io.github.graphmaquina.api.IgnoreReason;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.test.internal.Checks;
import java.util.Objects;

/**
 * Chainable, typed assertion over an {@link Outcome.Ignored}, obtained from {@link
 * OutcomeAssert#isIgnored()}. A reevaluation selected no edge, so no action ran and nothing moved: the
 * recorded state is the origin, no identifier was requested, and {@link #hasReason(IgnoreReason)} says
 * whether the state declared no watch at all or no rule of its watch held.
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

    public IgnoredOutcomeAssert<E, S, T> hasReason(IgnoreReason expected) {
        Objects.requireNonNull(expected, "expected");
        Checks.equal(context, "reason", expected, ignored.reason());
        return this;
    }
}
