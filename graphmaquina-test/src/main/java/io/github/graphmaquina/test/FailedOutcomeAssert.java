package io.github.graphmaquina.test;

import io.github.graphmaquina.api.FailureKind;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.test.internal.Checks;
import java.util.Optional;
import lombok.NonNull;

/**
 * Chainable, typed assertion over an {@link Outcome.Failed}, obtained from {@link
 * OutcomeAssert#isFailed()}. A failed outcome carries the destination of the edge that failed, the
 * identifier of the recovery edge that ran instead, the state that edge landed on, which
 * {@link #movedTo(Enum)} reads, and the failure kind the failure had, if any. Returned by a root call, it
 * was committed; returned by a cascade, it is written only if its root commits. The failure kind is compared
 * by identity, and the cause, which is optional, only ever by type or by reference — no expectation here
 * reads its message.
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

    /**
     * The destination of the edge that failed, which the entity did not reach through that edge:
     * {@link #movedTo(Enum)} reads where the recovery edge landed instead.
     */
    public FailedOutcomeAssert<E, S, T> hasTarget(@NonNull S expected) {
        Checks.equal(context, "target", expected, failed.to());
        return this;
    }

    /** The identifier of the recovery edge the engine resolved from the failed edge's origin. */
    public FailedOutcomeAssert<E, S, T> hasFailureTransition(@NonNull T expected) {
        Checks.equal(context, "failure transition", expected, failed.failureTransition());
        return this;
    }

    /**
     * The failure kind the failure carries, compared by identity: no class satisfies it but that very one. A failure
     * without a kind reports a null actual value.
     *
     * @param expected the failure kind the failure must carry
     * @return this view
     */
    public FailedOutcomeAssert<E, S, T> hasFailureKind(@NonNull Class<? extends FailureKind> expected) {
        Checks.same(context, "failure kind", expected, failed.failureKind().orElse(null));
        return this;
    }

    public FailedOutcomeAssert<E, S, T> hasNoFailureKind() {
        Optional<Class<? extends FailureKind>> none = Optional.empty();
        Checks.equal(context, "failure kind", none, failed.failureKind());
        return this;
    }

    /**
     * The state the recovery edge landed on: committed when a root call returned this outcome, and written only if
     * its root commits when a cascade did.
     */
    public FailedOutcomeAssert<E, S, T> movedTo(@NonNull S expected) {
        Checks.equal(context, "moved to", expected, failed.movedTo());
        return this;
    }

    public FailedOutcomeAssert<E, S, T> hasReason(@NonNull String expected) {
        Checks.equal(context, "reason", expected, failed.reason());
        return this;
    }

    /** Satisfied by any cause the type accepts, subclasses included; a failure without a cause never does. */
    public FailedOutcomeAssert<E, S, T> hasCause(@NonNull Class<? extends Throwable> expected) {
        Optional<Throwable> cause = failed.cause();
        if (cause.isEmpty() || !expected.isInstance(cause.get())) {
            Checks.fail(context, "cause", expected, cause.orElse(null));
        }
        return this;
    }

    /** Compares by reference, so a distinct throwable of the same type does not satisfy it. */
    public FailedOutcomeAssert<E, S, T> hasCauseSameAs(@NonNull Throwable expected) {
        Checks.same(context, "cause", expected, failed.cause().orElse(null));
        return this;
    }

    public FailedOutcomeAssert<E, S, T> hasNoCause() {
        Optional<Throwable> none = Optional.empty();
        Checks.equal(context, "cause", none, failed.cause());
        return this;
    }
}
