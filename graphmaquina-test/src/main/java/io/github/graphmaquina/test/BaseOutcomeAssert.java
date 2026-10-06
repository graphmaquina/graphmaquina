package io.github.graphmaquina.test;

import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.OutcomeCode;
import io.github.graphmaquina.api.OutcomeStatus;
import io.github.graphmaquina.test.internal.Checks;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Expectations every {@link Outcome} variant shares, returning the concrete view so that a chain keeps
 * the narrowed type. Three readings of a transition are kept apart on purpose:
 * {@link #requestedTransition(Enum)} is the identifier that was asked for, {@link #hasRecordedState(Enum)}
 * is the state the outcome itself records, and {@link #hasEntityState(Function, Enum)} reads
 * the live entity at the moment the expectation runs. They diverge whenever something moves the entity
 * away from the state the outcome records, such as an after-commit hook opening its own transition.
 *
 * <p>There is no shared reading of a requested destination. An identifier without an edge has no
 * destination to report, so only the variants that ran an edge expose one.
 *
 * @param <E> entity type of the machine
 * @param <S> state enum of the machine
 * @param <T> transition identifier enum of the machine
 * @param <SELF> the concrete view returned by every expectation
 */
public abstract class BaseOutcomeAssert<E, S extends Enum<S>, T extends Enum<T>,
        SELF extends BaseOutcomeAssert<E, S, T, SELF>> {

    /** Prefix of every failure message raised by this view. */
    final String context;

    /** The outcome under test, null only for a view built straight from a null subject. */
    final Outcome<E, S, T> actual;

    BaseOutcomeAssert(String context, Outcome<E, S, T> actual) {
        this.context = context;
        this.actual = actual;
    }

    /** The concrete view, so that an inherited expectation returns the narrowed type. */
    protected abstract SELF self();

    /** The state the transition started from, which every variant reports. */
    public SELF from(S expected) {
        Objects.requireNonNull(expected, "expected");
        Outcome<E, S, T> outcome = Checks.subject(context, "from", expected, actual);
        Checks.equal(context, "from", expected, outcome.from());
        return self();
    }

    /** The identifier the caller asked for, whether or not the engine ever ran its edge. */
    public SELF requestedTransition(T expected) {
        Objects.requireNonNull(expected, "expected");
        Optional<T> wanted = Optional.of(expected);
        Outcome<E, S, T> outcome = Checks.subject(context, "requested transition", wanted, actual);
        Checks.equal(context, "requested transition", wanted, outcome.requestedTransition());
        return self();
    }

    /** No identifier was requested, which is what a reevaluation that selected no edge reports. */
    public SELF hasNoRequestedTransition() {
        Optional<T> wanted = Optional.empty();
        Outcome<E, S, T> outcome = Checks.subject(context, "requested transition", wanted, actual);
        Checks.equal(context, "requested transition", wanted, outcome.requestedTransition());
        return self();
    }

    /**
     * The state the outcome itself records: the destination for an applied one, the state the
     * failure route landed on for a failed one, and the origin for a rejected or ignored one.
     */
    public SELF hasRecordedState(S expected) {
        Objects.requireNonNull(expected, "expected");
        Outcome<E, S, T> outcome = Checks.subject(context, "recorded state", expected, actual);
        Checks.equal(context, "recorded state", expected, outcome.recordedState());
        return self();
    }

    /**
     * Reads the entity through {@code getter} once, when this expectation runs rather than when the view
     * was built, so the answer is the entity's state now and not a value captured earlier.
     */
    public SELF hasEntityState(Function<? super E, ? extends S> getter, S expected) {
        Objects.requireNonNull(getter, "getter");
        Objects.requireNonNull(expected, "expected");
        Outcome<E, S, T> outcome = Checks.subject(context, "entity state", expected, actual);
        S observed = getter.apply(outcome.entity());
        Checks.equal(context, "entity state", expected, observed);
        return self();
    }

    /** Entity selection is by reference, so a distinct instance that compares equal does not satisfy it. */
    public SELF hasEntitySameAs(E expected) {
        Objects.requireNonNull(expected, "expected");
        Outcome<E, S, T> outcome = Checks.subject(context, "entity", expected, actual);
        Checks.same(context, "entity", expected, outcome.entity());
        return self();
    }

    /** The status this outcome reports, which every variant carries. */
    public SELF hasStatus(OutcomeStatus expected) {
        Objects.requireNonNull(expected, "expected");
        Outcome<E, S, T> outcome = Checks.subject(context, "status", expected, actual);
        Checks.equal(context, "status", expected, outcome.status());
        return self();
    }

    /** The code this outcome reports: its status refined by the kind of rejection or why nothing was selected. */
    public SELF hasCode(OutcomeCode expected) {
        Objects.requireNonNull(expected, "expected");
        Outcome<E, S, T> outcome = Checks.subject(context, "code", expected, actual);
        Checks.equal(context, "code", expected, outcome.code());
        return self();
    }

    /** The text the graph's author wrote for this outcome, compared by value. */
    public SELF hasExplanation(String expected) {
        Objects.requireNonNull(expected, "expected");
        Optional<String> wanted = Optional.of(expected);
        Outcome<E, S, T> outcome = Checks.subject(context, "explanation", wanted, actual);
        Checks.equal(context, "explanation", wanted, outcome.explanation());
        return self();
    }

    /** No text of the author's: an applied or ignored outcome, or a failure routed from an exception. */
    public SELF hasNoExplanation() {
        Optional<String> wanted = Optional.empty();
        Outcome<E, S, T> outcome = Checks.subject(context, "explanation", wanted, actual);
        Checks.equal(context, "explanation", wanted, outcome.explanation());
        return self();
    }

    /**
     * The hops the outcome's root call committed, which may belong to several machines when it cascaded; empty for
     * an outcome that committed nothing and for one a cascade returned.
     */
    public TransitionRecordsAssert records() {
        return new TransitionRecordsAssert(context + ".records", actual == null ? null : actual.records());
    }

    /**
     * The watch re-evaluations the outcome's commit triggered; one that threw an exception is reported here as an
     * aborted reaction instead of propagating, while an {@link Error} is not caught. Empty for an outcome that
     * committed nothing.
     */
    public ReactionsAssert reactions() {
        return new ReactionsAssert(context + ".reactions", actual == null ? null : actual.reactions());
    }
}
