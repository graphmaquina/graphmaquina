package io.github.graphmaquina.test;

import io.github.graphmaquina.api.Outcome;

/**
 * Chainable, typed assertion over an {@link Outcome.Ignored}, obtained from {@link
 * OutcomeAssert#isIgnored()}. Nothing ran and nothing moved, so the shared expectations are the whole
 * contract: the requested target is still readable, and the recorded state is the origin.
 *
 * @param <E> entity type of the machine
 * @param <S> state enum of the machine
 */
public final class IgnoredOutcomeAssert<E, S extends Enum<S>>
        extends BaseOutcomeAssert<E, S, IgnoredOutcomeAssert<E, S>> {

    IgnoredOutcomeAssert(Outcome.Ignored<E, S> ignored) {
        super("ignored outcome", ignored);
    }

    @Override
    protected IgnoredOutcomeAssert<E, S> self() {
        return this;
    }
}
