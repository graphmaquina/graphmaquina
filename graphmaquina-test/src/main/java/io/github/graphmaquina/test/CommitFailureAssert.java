package io.github.graphmaquina.test;

import io.github.graphmaquina.error.CommitFailedException;
import io.github.graphmaquina.test.internal.Checks;
import java.util.List;

/**
 * Chainable assertion over a {@link CommitFailedException}: phase 2 failed partway through writing, so the
 * two lists are the whole story. Anything in {@code applied} was written and not undone — the system was
 * left inconsistent — and anything in {@code pending} never got written at all. Both are compared entity by
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
