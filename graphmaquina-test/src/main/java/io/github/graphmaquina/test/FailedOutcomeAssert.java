package io.github.graphmaquina.test;

import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.test.internal.Checks;
import java.util.Objects;
import java.util.Optional;

/**
 * Chainable, typed assertion over an {@link Outcome.Failed}, obtained from {@link
 * OutcomeAssert#isFailed()}. A failed outcome commits: it carries both the target that was requested
 * and the failure route it actually landed on, and {@link #movedTo(Enum)} reads the route. The cause is
 * optional and is only ever compared by type or by reference — no expectation here reads its message.
 *
 * @param <E> entity type of the machine
 * @param <S> state enum of the machine
 */
public final class FailedOutcomeAssert<E, S extends Enum<S>>
        extends BaseOutcomeAssert<E, S, FailedOutcomeAssert<E, S>> {

    private final Outcome.Failed<E, S> failed;

    FailedOutcomeAssert(Outcome.Failed<E, S> failed) {
        super("failed outcome", failed);
        this.failed = failed;
    }

    @Override
    protected FailedOutcomeAssert<E, S> self() {
        return this;
    }

    /** The failure route the entity was committed to, which is not the target that was requested. */
    public FailedOutcomeAssert<E, S> movedTo(S expected) {
        Objects.requireNonNull(expected, "expected");
        Checks.equal(context, "moved to", expected, failed.movedTo());
        return this;
    }

    public FailedOutcomeAssert<E, S> hasReason(String expected) {
        Objects.requireNonNull(expected, "expected");
        Checks.equal(context, "reason", expected, failed.reason());
        return this;
    }

    /** Satisfied by any cause the type accepts, subclasses included; a failure without a cause never does. */
    public FailedOutcomeAssert<E, S> hasCause(Class<? extends Throwable> expected) {
        Objects.requireNonNull(expected, "expected");
        Optional<Throwable> cause = failed.cause();
        if (cause.isEmpty() || !expected.isInstance(cause.get())) {
            Checks.fail(context, "cause", expected, cause.orElse(null));
        }
        return this;
    }

    /** Compares by reference, so a distinct throwable of the same type does not satisfy it. */
    public FailedOutcomeAssert<E, S> hasCauseSameAs(Throwable expected) {
        Objects.requireNonNull(expected, "expected");
        Checks.same(context, "cause", expected, failed.cause().orElse(null));
        return this;
    }

    public FailedOutcomeAssert<E, S> hasNoCause() {
        Optional<Throwable> none = Optional.empty();
        Checks.equal(context, "cause", none, failed.cause());
        return this;
    }

    /** The hops this transaction committed, the failure route included, since a failed outcome still commits. */
    public TransitionRecordsAssert records() {
        return TransitionRecordsAssert.assertThat(failed.records());
    }

    /** The watch re-evaluations this commit triggered; the failure route is a commit, so it triggers them too. */
    public ReactionsAssert reactions() {
        return ReactionsAssert.assertThat(failed.reactions());
    }
}
