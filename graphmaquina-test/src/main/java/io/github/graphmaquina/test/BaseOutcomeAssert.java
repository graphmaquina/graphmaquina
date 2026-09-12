package io.github.graphmaquina.test;

import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.test.internal.Checks;
import java.util.Objects;
import java.util.function.Function;

/**
 * Expectations every {@link Outcome} variant shares, returning the concrete view so that a chain keeps
 * the narrowed type. Three readings of "where the entity is" are kept apart on purpose: {@link
 * #requestedTarget(Enum)} is the transition that was asked for, {@link #hasRecordedState(Enum)} is the
 * state the outcome itself records as current, and {@link #hasEntityState(Function, Enum)} reads the
 * live entity at the moment the expectation runs. They diverge whenever something moved the entity
 * after the outcome was produced, such as an after-commit hook opening its own transition.
 *
 * @param <E> entity type of the machine
 * @param <S> state enum of the machine
 * @param <SELF> the concrete view returned by every expectation
 */
public abstract class BaseOutcomeAssert<E, S extends Enum<S>, SELF extends BaseOutcomeAssert<E, S, SELF>> {

    /** Prefix of every failure message raised by this view. */
    final String context;

    /** The outcome under test, null only for a view built straight from a null subject. */
    final Outcome<E, S> actual;

    BaseOutcomeAssert(String context, Outcome<E, S> actual) {
        this.context = context;
        this.actual = actual;
    }

    /** The concrete view, so that an inherited expectation returns the narrowed type. */
    protected abstract SELF self();

    /** The state the transition started from, which every variant reports. */
    public SELF from(S expected) {
        Objects.requireNonNull(expected, "expected");
        Outcome<E, S> outcome = Checks.subject(context, "from", expected, actual);
        Checks.equal(context, "from", expected, outcome.from());
        return self();
    }

    /** The target the transition was asked for, whether or not the engine ever moved there. */
    public SELF requestedTarget(S expected) {
        Objects.requireNonNull(expected, "expected");
        Outcome<E, S> outcome = Checks.subject(context, "requested target", expected, actual);
        Checks.equal(context, "requested target", expected, targetOf(outcome));
        return self();
    }

    /**
     * The state the outcome itself records as current: the destination for an applied one, the failure
     * route for a failed one, and the origin for a rejected or ignored one.
     */
    public SELF hasRecordedState(S expected) {
        Objects.requireNonNull(expected, "expected");
        Outcome<E, S> outcome = Checks.subject(context, "recorded state", expected, actual);
        Checks.equal(context, "recorded state", expected, outcome.current());
        return self();
    }

    /**
     * Reads the entity through {@code getter} once, when this expectation runs rather than when the view
     * was built, so the answer is the entity's state now and not a value captured earlier.
     */
    public SELF hasEntityState(Function<? super E, ? extends S> getter, S expected) {
        Objects.requireNonNull(getter, "getter");
        Objects.requireNonNull(expected, "expected");
        Outcome<E, S> outcome = Checks.subject(context, "entity state", expected, actual);
        S observed = getter.apply(outcome.entity());
        Checks.equal(context, "entity state", expected, observed);
        return self();
    }

    /** Entity selection is by reference, so a distinct instance that compares equal does not satisfy it. */
    public SELF hasEntitySameAs(E expected) {
        Objects.requireNonNull(expected, "expected");
        Outcome<E, S> outcome = Checks.subject(context, "entity", expected, actual);
        Checks.same(context, "entity", expected, outcome.entity());
        return self();
    }

    private static <E, S extends Enum<S>> S targetOf(Outcome<E, S> outcome) {
        return switch (outcome) {
            case Outcome.Applied<E, S> applied -> applied.to();
            case Outcome.Failed<E, S> failed -> failed.to();
            case Outcome.Rejected<E, S> rejected -> rejected.to();
            case Outcome.Ignored<E, S> ignored -> ignored.to();
        };
    }
}
