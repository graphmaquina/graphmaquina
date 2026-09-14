package io.github.graphmaquina.test;

import io.github.graphmaquina.error.CommitFailedException;
import io.github.graphmaquina.test.internal.Checks;
import java.util.List;

/**
 * Chainable assertion over a {@link CommitFailedException}: phase 2 could not complete the commit.
 * {@code applied} records entities whose setter calls completed; {@code pending} records the remaining planned
 * commit work, including the failing setter when one threw. That setter may have mutated its entity before
 * throwing, so inspect entity state or a {@link StateSnapshot} separately. Both lists are compared entity by
 * entity, by reference and in order, so a rollback expectation is never satisfied by the exception type alone.
 */
public final class CommitFailureAssert extends BaseFailureAssert<CommitFailureAssert> {

    private final CommitFailedException failure;

    CommitFailureAssert(String context, CommitFailedException failure) {
        super(context, failure);
        this.failure = failure;
    }

    @Override
    protected CommitFailureAssert self() {
        return this;
    }

    public CommitFailureAssert hasAppliedExactly(Object... expected) {
        return hasExactly("applied", expected, failure.applied());
    }

    public CommitFailureAssert hasPendingExactly(Object... expected) {
        return hasExactly("pending", expected, failure.pending());
    }

    private CommitFailureAssert hasExactly(String property, Object[] expected, List<Object> entities) {
        List<Object> wanted = List.of(expected);
        Checks.equal(context, property + " size", wanted.size(), entities.size());
        for (int index = 0; index < wanted.size(); index++) {
            Checks.same(context + "[" + index + "]", property, wanted.get(index), entities.get(index));
        }
        return this;
    }
}
