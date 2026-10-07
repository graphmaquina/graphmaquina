package io.github.graphmaquina.test;

import io.github.graphmaquina.error.CommitFailedException;
import io.github.graphmaquina.test.internal.Checks;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Chainable assertion over a {@link CommitFailedException}: phase 2 could not complete the commit. {@code applied}
 * records the entities whose setter returned, {@code failedEntity} the one whose setter threw, which may have mutated
 * its entity before throwing, so inspect entity state or a {@link StateSnapshot} separately, and {@code pending} the
 * ones never tried. The lists are compared entity by entity, by reference and in order, so a rollback expectation is
 * never satisfied by the exception type alone.
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

    /** The entity whose setter threw, matched by reference. */
    public CommitFailureAssert hasFailedEntity(Object expected) {
        Objects.requireNonNull(expected, "expected");
        Optional<Object> actual = failure.failedEntity();
        if (actual.isEmpty() || actual.get() != expected) {
            Checks.fail(context, "failed entity", expected, actual.orElse(null));
        }
        return this;
    }

    /** No setter threw: phase 2 stopped before its first write. */
    public CommitFailureAssert hasNoFailedEntity() {
        Checks.equal(context, "failed entity", Optional.empty(), failure.failedEntity());
        return this;
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
