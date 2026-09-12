package io.github.graphmaquina.test;

import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.test.internal.Checks;
import java.util.Objects;
import java.util.Optional;

/**
 * Chainable, typed assertion over an {@link Outcome.Rejected}, obtained from {@link
 * OutcomeAssert#isRejected()}. Nothing ran, so the entity stays where it was. The two rejections the
 * engine produces are told apart by the guard name: a forbidden cell names none, while a guard that
 * rejected names itself.
 *
 * @param <E> entity type of the machine
 * @param <S> state enum of the machine
 */
public final class RejectedOutcomeAssert<E, S extends Enum<S>>
        extends BaseOutcomeAssert<E, S, RejectedOutcomeAssert<E, S>> {

    private final Outcome.Rejected<E, S> rejected;

    RejectedOutcomeAssert(Outcome.Rejected<E, S> rejected) {
        super("rejected outcome", rejected);
        this.rejected = rejected;
    }

    @Override
    protected RejectedOutcomeAssert<E, S> self() {
        return this;
    }

    /** A forbidden cell rejects without naming a guard, which is what distinguishes it from a guard rejection. */
    public RejectedOutcomeAssert<E, S> isForbidden() {
        Optional<String> none = Optional.empty();
        Checks.equal(context, "guard", none, rejected.guard());
        return this;
    }

    public RejectedOutcomeAssert<E, S> isRejectedByGuardNamed(String expected) {
        Objects.requireNonNull(expected, "expected");
        Checks.equal(context, "guard", Optional.of(expected), rejected.guard());
        return this;
    }

    public RejectedOutcomeAssert<E, S> hasReason(String expected) {
        Objects.requireNonNull(expected, "expected");
        Checks.equal(context, "reason", expected, rejected.reason());
        return this;
    }
}
