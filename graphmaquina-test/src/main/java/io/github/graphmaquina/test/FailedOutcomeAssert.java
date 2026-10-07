package io.github.graphmaquina.test;

import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.test.internal.Checks;
import java.util.Objects;
import java.util.Optional;

/**
 * Chainable, typed assertion over an {@link Outcome.Failed}, obtained from {@link
 * OutcomeAssert#isFailed()}. A failed outcome commits: it carries the destination of the edge that
 * failed, the identifier of the recovery edge and the state it actually landed on, and
 * {@link #movedTo(Enum)} reads that landing. The cause is optional and is only ever compared by type or
 * by reference — no expectation here reads its message.
 *
 * @param <E> entity type of the machine
 * @param <S> state enum of the machine
 * @param <T> transition identifier enum of the machine
 */
public final class FailedOutcomeAssert<E, S extends Enum<S>, T extends Enum<T>>
        extends BaseOutcomeAssert<E, S, T, FailedOutcomeAssert<E, S, T>> {

    private final Outcome.Failed<E, S, T> failed;

    FailedOutcomeAssert(String context, Outcome.Failed<E, S, T> failed) {
        super(context, failed);
        this.failed = failed;
    }

    @Override
    protected FailedOutcomeAssert<E, S, T> self() {
        return this;
    }

    /** The destination of the edge that failed, which is not where the entity was committed. */
    public FailedOutcomeAssert<E, S, T> hasTarget(S expected) {
        Objects.requireNonNull(expected, "expected");
        Checks.equal(context, "target", expected, failed.to());
        return this;
    }

    /** The identifier of the recovery edge the engine resolved from the failed edge's origin. */
    public FailedOutcomeAssert<E, S, T> hasFailureTransition(T expected) {
        Objects.requireNonNull(expected, "expected");
        Checks.equal(context, "failure transition", expected, failed.failureTransition());
        return this;
    }

    /** The state the recovery edge committed, which is not the destination that was asked for. */
    public FailedOutcomeAssert<E, S, T> movedTo(S expected) {
        Objects.requireNonNull(expected, "expected");
        Checks.equal(context, "moved to", expected, failed.movedTo());
        return this;
    }

    public FailedOutcomeAssert<E, S, T> hasReason(String expected) {
        Objects.requireNonNull(expected, "expected");
        Checks.equal(context, "reason", expected, failed.reason());
        return this;
    }

    /** Satisfied by any cause the type accepts, subclasses included; a failure without a cause never does. */
    public FailedOutcomeAssert<E, S, T> hasCause(Class<? extends Throwable> expected) {
        Objects.requireNonNull(expected, "expected");
        Optional<Throwable> cause = failed.cause();
        if (cause.isEmpty() || !expected.isInstance(cause.get())) {
            Checks.fail(context, "cause", expected, cause.orElse(null));
        }
        return this;
    }

    /** Compares by reference, so a distinct throwable of the same type does not satisfy it. */
    public FailedOutcomeAssert<E, S, T> hasCauseSameAs(Throwable expected) {
        Objects.requireNonNull(expected, "expected");
        Checks.same(context, "cause", expected, failed.cause().orElse(null));
        return this;
    }

    public FailedOutcomeAssert<E, S, T> hasNoCause() {
        Optional<Throwable> none = Optional.empty();
        Checks.equal(context, "cause", none, failed.cause());
        return this;
    }
}
