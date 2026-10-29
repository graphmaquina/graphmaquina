package io.github.graphmaquina.test;

import io.github.graphmaquina.api.AutomaticStep;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.OutcomeCode;
import io.github.graphmaquina.api.OutcomeStatus;
import io.github.graphmaquina.test.internal.AutomaticSteps;
import io.github.graphmaquina.test.internal.CheckedTypes;
import io.github.graphmaquina.test.internal.Checks;
import java.util.Optional;
import java.util.function.Function;
import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

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
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public abstract class BaseOutcomeAssert<E, S extends Enum<S>, T extends Enum<T>,
        SELF extends BaseOutcomeAssert<E, S, T, SELF>> {

    private static final String AUTOMATIC_FAILURE = "automatic failure";
    private static final String AUTOMATIC_ABORT = "automatic abort";
    private static final String AUTOMATIC_BLOCK = "automatic block";
    private static final String FAILED_ATTEMPT = "a failed automatic attempt";
    private static final String ABORTED_STEP = "an aborted automatic step";
    private static final String BLOCKED_ATTEMPT = "a blocked automatic attempt";
    private static final String NONE = "none";
    private static final String AUTOMATIC_FAILURE_VIEW = ".automaticFailure (failed)";
    private static final String AUTOMATIC_BLOCK_VIEW = ".automaticBlock (ignored)";

    /** Prefix of every failure message raised by this view. */
    final String context;

    /** The outcome under test, null only for a view built straight from a null subject. */
    final Outcome<E, S, T> actual;

    /** The concrete view, so that an inherited expectation returns the narrowed type. */
    protected abstract SELF self();

    /** The state the transition started from, which every variant reports. */
    public SELF from(@NonNull S expected) {
        Outcome<E, S, T> outcome = Checks.subject(context, "from", expected, actual);
        Checks.equal(context, "from", expected, outcome.from());
        return self();
    }

    /** The identifier the caller asked for, whether or not the engine ever ran its edge. */
    public SELF requestedTransition(@NonNull T expected) {
        Optional<T> wanted = Optional.of(expected);
        Outcome<E, S, T> outcome = Checks.subject(context, "requested transition", wanted, actual);
        Checks.equal(context, "requested transition", wanted, outcome.requestedTransition());
        return self();
    }

    /** No identifier was requested, which is what a reevaluation or an advance that ran no edge reports. */
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
    public SELF hasRecordedState(@NonNull S expected) {
        Outcome<E, S, T> outcome = Checks.subject(context, "recorded state", expected, actual);
        Checks.equal(context, "recorded state", expected, outcome.recordedState());
        return self();
    }

    /**
     * Reads the entity through {@code getter} once, when this expectation runs rather than when the view
     * was built, so the answer is the entity's state now and not a value captured earlier.
     */
    public SELF hasEntityState(@NonNull Function<? super E, ? extends S> getter, @NonNull S expected) {
        Outcome<E, S, T> outcome = Checks.subject(context, "entity state", expected, actual);
        S observed = getter.apply(outcome.entity());
        Checks.equal(context, "entity state", expected, observed);
        return self();
    }

    /** Entity selection is by reference, so a distinct instance that compares equal does not satisfy it. */
    public SELF hasEntitySameAs(@NonNull E expected) {
        Outcome<E, S, T> outcome = Checks.subject(context, "entity", expected, actual);
        Checks.same(context, "entity", expected, outcome.entity());
        return self();
    }

    /** The status this outcome reports, which every variant carries. */
    public SELF hasStatus(@NonNull OutcomeStatus expected) {
        Outcome<E, S, T> outcome = Checks.subject(context, "status", expected, actual);
        Checks.equal(context, "status", expected, outcome.status());
        return self();
    }

    /** The code this outcome reports: its status refined by the kind of rejection or why nothing was selected. */
    public SELF hasCode(@NonNull OutcomeCode expected) {
        Outcome<E, S, T> outcome = Checks.subject(context, "code", expected, actual);
        Checks.equal(context, "code", expected, outcome.code());
        return self();
    }

    /**
     * The reason given for this outcome, compared by value: the graph author's, or the library's fixed text for a
     * terminal state that declares no policy and for a detached sub-machine or a reserved exit.
     */
    public SELF hasExplanation(@NonNull String expected) {
        Optional<String> wanted = Optional.of(expected);
        Outcome<E, S, T> outcome = Checks.subject(context, "explanation", wanted, actual);
        Checks.equal(context, "explanation", wanted, outcome.explanation());
        return self();
    }

    /** No reason given: an applied or ignored outcome, or a failure routed from an exception. */
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
        return new TransitionRecordsAssert(context + ".records", outcome().map(Outcome::records));
    }

    /**
     * The watch re-evaluations the outcome's commit triggered; one that threw an exception is reported here as an
     * aborted reaction instead of propagating, while an {@link Error} is not caught. Empty for an outcome that
     * committed nothing and for one a cascade returned.
     */
    public ReactionsAssert reactions() {
        return new ReactionsAssert(context + ".reactions", actual == null ? null : actual.reactions());
    }

    /**
     * The automatic attempts the outcome's commit triggered; one that threw is reported here as an aborted step instead
     * of propagating, while an {@link Error} is not caught. Empty for an outcome that committed nothing and for one a
     * cascade returned.
     */
    public AutomaticStepsAssert automaticSteps() {
        return new AutomaticStepsAssert(context + ".automaticSteps", actual == null ? null : actual.automaticSteps());
    }

    /**
     * The first automatic attempt of the call that ended in a failure, as {@link Outcome#automaticFailure()} searches
     * for it, whether its route stopped the chain or let it go on. It fails when no attempt failed. The search takes
     * the first of any machine, a peer's included when a watch reaction ran its chain, and this view takes that attempt
     * to belong to the machine of this outcome, which it checks by its entity and its enums: one of another machine
     * raises {@link IllegalArgumentException}, whose message names that machine. {@link
     * #automaticFailure(MachineKey)} is the form that selects the attempts of one machine.
     */
    public FailedOutcomeAssert<E, S, T> automaticFailure() {
        Outcome<E, S, T> outcome = Checks.subject(context, AUTOMATIC_FAILURE, FAILED_ATTEMPT, actual);
        Outcome.Failed<?, ?, ?> found = Checks.present(context, AUTOMATIC_FAILURE, FAILED_ATTEMPT,
                outcome.automaticFailure());
        try {
            return new FailedOutcomeAssert<>(context + AUTOMATIC_FAILURE_VIEW, CheckedTypes.failedOf(outcome, found));
        } catch (IllegalArgumentException mismatch) {
            throw ofAnotherMachine(outcome, found, mismatch, "automaticFailure");
        }
    }

    /**
     * The first automatic attempt of {@code key}'s machine that ended in a failure, searched in the order {@link
     * #automaticFailure()} uses but skipping the attempts of every other machine, so a peer's failure found first
     * through a watch reaction does not hide this machine's own. The view is typed by {@code key}, whose classes the
     * attempt is checked against. It fails when that machine has no failed attempt.
     */
    public <E2, S2 extends Enum<S2>, T2 extends Enum<T2>> FailedOutcomeAssert<E2, S2, T2> automaticFailure(
            @NonNull MachineKey<E2, S2, T2> key) {
        Outcome<E, S, T> outcome = Checks.subject(context, AUTOMATIC_FAILURE, FAILED_ATTEMPT + " of " + name(key),
                actual);
        Outcome.Failed<?, ?, ?> found = Checks.present(context, AUTOMATIC_FAILURE,
                FAILED_ATTEMPT + " of " + name(key), AutomaticSteps.failure(outcome, key));
        return new FailedOutcomeAssert<>(context + AUTOMATIC_FAILURE_VIEW,
                CheckedTypes.outcome(key, found).asFailed().orElseThrow());
    }

    /**
     * The first automatic attempt of the call that aborted, as {@link Outcome#automaticAbort()} searches for it. It
     * fails when no step aborted.
     */
    public AbortedAutomaticStepAssert<E, S, T> automaticAbort() {
        Outcome<E, S, T> outcome = Checks.subject(context, AUTOMATIC_ABORT, ABORTED_STEP, actual);
        AutomaticStep.Aborted found = Checks.present(context, AUTOMATIC_ABORT, ABORTED_STEP, outcome.automaticAbort());
        return new AbortedAutomaticStepAssert<>(context + ".automaticAbort", found);
    }

    /**
     * The first automatic step of {@code key}'s machine that aborted, searched in the order {@link
     * #automaticAbort()} uses but skipping the steps of every other machine. The view is typed by {@code key}. It
     * fails when that machine has no aborted step.
     */
    public <E2, S2 extends Enum<S2>, T2 extends Enum<T2>> AbortedAutomaticStepAssert<E2, S2, T2> automaticAbort(
            @NonNull MachineKey<E2, S2, T2> key) {
        Outcome<E, S, T> outcome = Checks.subject(context, AUTOMATIC_ABORT, ABORTED_STEP + " of " + name(key), actual);
        AutomaticStep.Aborted found = Checks.present(context, AUTOMATIC_ABORT, ABORTED_STEP + " of " + name(key),
                AutomaticSteps.abort(outcome, key));
        return new AbortedAutomaticStepAssert<>(context + ".automaticAbort", found);
    }

    /**
     * The first automatic attempt of the call that admitted none of its candidates, as {@link Outcome#automaticBlock()}
     * searches for it, whose {@link IgnoredOutcomeAssert#rejections()} say why. It fails when no attempt was blocked,
     * and it takes the attempt to belong to the machine of this outcome, and raises
     * {@link IllegalArgumentException} for another's, as {@link #automaticFailure()} does.
     */
    public IgnoredOutcomeAssert<E, S, T> automaticBlock() {
        Outcome<E, S, T> outcome = Checks.subject(context, AUTOMATIC_BLOCK, BLOCKED_ATTEMPT, actual);
        Outcome.Ignored<?, ?, ?> found = Checks.present(context, AUTOMATIC_BLOCK, BLOCKED_ATTEMPT,
                outcome.automaticBlock());
        try {
            return new IgnoredOutcomeAssert<>(context + AUTOMATIC_BLOCK_VIEW, CheckedTypes.ignoredOf(outcome, found));
        } catch (IllegalArgumentException mismatch) {
            throw ofAnotherMachine(outcome, found, mismatch, "automaticBlock");
        }
    }

    /**
     * The first automatic attempt of {@code key}'s machine that admitted none of its candidates, searched in the order
     * {@link #automaticBlock()} uses but skipping the attempts of every other machine. The view is typed by
     * {@code key}. It fails when that machine has no blocked attempt.
     */
    public <E2, S2 extends Enum<S2>, T2 extends Enum<T2>> IgnoredOutcomeAssert<E2, S2, T2> automaticBlock(
            @NonNull MachineKey<E2, S2, T2> key) {
        Outcome<E, S, T> outcome = Checks.subject(context, AUTOMATIC_BLOCK, BLOCKED_ATTEMPT + " of " + name(key),
                actual);
        Outcome.Ignored<?, ?, ?> found = Checks.present(context, AUTOMATIC_BLOCK,
                BLOCKED_ATTEMPT + " of " + name(key), AutomaticSteps.block(outcome, key));
        return new IgnoredOutcomeAssert<>(context + AUTOMATIC_BLOCK_VIEW,
                CheckedTypes.outcome(key, found).asIgnored().orElseThrow());
    }

    /** No automatic attempt of the call failed; the failure names the outcome of the first one that did. */
    public SELF hasNoAutomaticFailure() {
        Outcome<E, S, T> outcome = Checks.subject(context, AUTOMATIC_FAILURE, NONE, actual);
        outcome.automaticFailure().ifPresent((Outcome.Failed<?, ?, ?> failed) ->
                Checks.fail(context, AUTOMATIC_FAILURE, NONE, failed));
        return self();
    }

    /** No automatic step of the call aborted; the failure carries the cause of the first one that did. */
    public SELF hasNoAutomaticAbort() {
        Outcome<E, S, T> outcome = Checks.subject(context, AUTOMATIC_ABORT, NONE, actual);
        outcome.automaticAbort().ifPresent((AutomaticStep.Aborted aborted) ->
                Checks.fail(context, AUTOMATIC_ABORT, NONE, aborted.cause()));
        return self();
    }

    /** No automatic attempt of the call was blocked; the failure carries the outcome of the first one that was. */
    public SELF hasNoAutomaticBlock() {
        Outcome<E, S, T> outcome = Checks.subject(context, AUTOMATIC_BLOCK, NONE, actual);
        outcome.automaticBlock().ifPresent((Outcome.Ignored<?, ?, ?> blocked) ->
                Checks.fail(context, AUTOMATIC_BLOCK, NONE, blocked));
        return self();
    }

    /**
     * The sub-machines the outcome's root call started, in log order, cascaded steps included; empty for an outcome
     * that committed nothing and for one a cascade returned.
     */
    public StartsAssert starts() {
        return new StartsAssert(context + ".starts", outcome().map(Outcome::starts));
    }

    /**
     * The flow of the call that returned the outcome, as {@link Outcome#flow()} describes it: the values its steps and
     * hooks left there, its root entity and, when its root machine records it, its trail. An ignored outcome, one a
     * cascade returned, and one whose root call wrote nothing to its flow and records no trail hand out
     * {@link io.github.graphmaquina.api.FlowView#empty()}, which holds no entity.
     */
    public FlowAssert flow() {
        return new FlowAssert(context + ".flow", outcome().map(Outcome::flow));
    }

    /** The key by its three classes in full, which the short form of {@code toString} does not give. */
    private static String name(MachineKey<?, ?, ?> key) {
        return key.entity().getName() + "/" + key.states().getName() + "/" + key.transitions().getName();
    }

    /**
     * The error of a search that found an attempt of a machine other than this outcome's: it keeps the mismatch, names
     * the machine of the step that holds the attempt and says which keyed form selects a machine's own.
     */
    private static IllegalArgumentException ofAnotherMachine(Outcome<?, ?, ?> outcome, Outcome<?, ?, ?> found,
            IllegalArgumentException mismatch, String search) {
        String machine = AutomaticSteps.keyOf(outcome, found).map(BaseOutcomeAssert::name).orElse("another machine");
        return new IllegalArgumentException(mismatch.getMessage() + "; the first attempt the call searched belongs to "
                + machine + ", so select the attempts of one machine with " + search + "(MachineKey)", mismatch);
    }

    /** The outcome under test, wrapped once: the field is null only for a view built from a null subject. */
    private Optional<Outcome<E, S, T>> outcome() {
        return Optional.ofNullable(actual);
    }
}
