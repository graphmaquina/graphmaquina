package io.github.graphmaquina.test;

import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.RejectionKind;
import io.github.graphmaquina.test.internal.Checks;
import java.util.Objects;
import java.util.Optional;

/**
 * Chainable, typed assertion over an {@link Outcome.Rejected}, obtained from {@link
 * OutcomeAssert#isRejected()}. Nothing ran, so the entity stays where it was and there is no destination
 * to read: the same identifier may reach different states from different origins. The two rejections the
 * engine produces are told apart by their {@link RejectionKind} — no edge leaves the origin under that
 * identifier, or a guard said no and named itself.
 *
 * @param <E> entity type of the machine
 * @param <S> state enum of the machine
 * @param <T> transition identifier enum of the machine
 */
public final class RejectedOutcomeAssert<E, S extends Enum<S>, T extends Enum<T>>
        extends BaseOutcomeAssert<E, S, T, RejectedOutcomeAssert<E, S, T>> {

    private final Outcome.Rejected<E, S, T> rejected;

    RejectedOutcomeAssert(Outcome.Rejected<E, S, T> rejected) {
        super("rejected outcome", rejected);
        this.rejected = rejected;
    }

    @Override
    protected RejectedOutcomeAssert<E, S, T> self() {
        return this;
    }

    /** No edge is keyed by the origin and the requested identifier, so no guard was ever reached. */
    public RejectedOutcomeAssert<E, S, T> hasNoTransition() {
        Checks.equal(context, "kind", RejectionKind.NO_TRANSITION, rejected.kind());
        return this;
    }

    /** The edge existed and a guard rejected it, which is what carries a guard name. */
    public RejectedOutcomeAssert<E, S, T> isRejectedByGuardNamed(String expected) {
        Objects.requireNonNull(expected, "expected");
        Checks.equal(context, "kind", RejectionKind.GUARD, rejected.kind());
        Checks.equal(context, "guard", Optional.of(expected), rejected.guard());
        return this;
    }

    public RejectedOutcomeAssert<E, S, T> hasReason(String expected) {
        Objects.requireNonNull(expected, "expected");
        Checks.equal(context, "reason", expected, rejected.reason());
        return this;
    }
}
