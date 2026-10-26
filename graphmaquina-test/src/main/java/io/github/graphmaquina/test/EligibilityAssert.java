package io.github.graphmaquina.test;

import io.github.graphmaquina.api.Eligibility;
import io.github.graphmaquina.api.RejectionKind;
import io.github.graphmaquina.test.internal.Checks;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Chainable, typed assertion over an {@link Eligibility}, the answer of an eligibility check. Nothing ran and
 * nothing was committed, and neither variant reports a destination, so there is none to read. One view serves
 * both variants and every expectation returns it, so a chain keeps its types.
 *
 * <p>{@link #isEligible()} and {@link #isIneligible()} assert the variant. The expectations about a refusal, which
 * are the kind ({@link #hasNoTransition()}, {@link #isRejectedByGuardNamed(String)}, {@link #isDetached()},
 * {@link #isReservedExit()}) and {@link #hasReason(String)}, need an ineligible answer: on an eligible one they fail
 * as an assertion on the variant, never with a cast. {@link #from(Enum)} and {@link #transition(Enum)} read what both
 * variants report.
 *
 * <p>A failure names the view {@code eligibility}. A null subject reaches the view untouched and fails, inspectably,
 * on the first expectation called on it.
 *
 * @param <E> entity type of the machine
 * @param <S> state enum of the machine
 * @param <T> transition identifier enum of the machine
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class EligibilityAssert<E, S extends Enum<S>, T extends Enum<T>> {

    private static final String CONTEXT = "eligibility";
    private static final String VARIANT = "variant";
    private static final String ELIGIBLE = "Eligible";
    private static final String INELIGIBLE = "Ineligible";

    private final Eligibility<E, S, T> actual;

    /** The view over {@code actual}, which may be null and then fails on the first expectation called on it. */
    public static <E, S extends Enum<S>, T extends Enum<T>> EligibilityAssert<E, S, T> assertThat(
            Eligibility<E, S, T> actual) {
        return new EligibilityAssert<>(actual);
    }

    /** The check found nothing that refuses the request from the state it read. */
    public EligibilityAssert<E, S, T> isEligible() {
        Eligibility<E, S, T> answer = Checks.subject(CONTEXT, VARIANT, ELIGIBLE, actual);
        Checks.equal(CONTEXT, VARIANT, ELIGIBLE, variantOf(answer));
        return this;
    }

    /** The check found a refusal, whose kind, guard and reason the other expectations then read. */
    public EligibilityAssert<E, S, T> isIneligible() {
        ineligible();
        return this;
    }

    /** No edge is keyed by the state the check read and the identifier it asked about, so no guard was reached. */
    public EligibilityAssert<E, S, T> hasNoTransition() {
        Checks.equal(CONTEXT, "kind", RejectionKind.NO_TRANSITION, ineligible().kind());
        return this;
    }

    /** The edge exists and a guard refuses it, which is what carries a guard name. */
    public EligibilityAssert<E, S, T> isRejectedByGuardNamed(@NonNull String expected) {
        Eligibility.Ineligible<E, S, T> answer = ineligible();
        Checks.equal(CONTEXT, "kind", RejectionKind.GUARD, answer.kind());
        Checks.equal(CONTEXT, "guard", Optional.of(expected), answer.guard());
        return this;
    }

    /** The entity is a sub-machine whose parent does not hold it, so the check stopped before any edge was resolved. */
    public EligibilityAssert<E, S, T> isDetached() {
        Checks.equal(CONTEXT, "kind", RejectionKind.DETACHED, ineligible().kind());
        return this;
    }

    /** The identifier is an exit of the sub-machine bound to the state the check read, which only its end runs. */
    public EligibilityAssert<E, S, T> isReservedExit() {
        Checks.equal(CONTEXT, "kind", RejectionKind.RESERVED_EXIT, ineligible().kind());
        return this;
    }

    /** The reason of the refusal, compared by value: the graph author's text or the library's fixed one. */
    public EligibilityAssert<E, S, T> hasReason(@NonNull String expected) {
        Checks.equal(CONTEXT, "reason", expected, ineligible().reason());
        return this;
    }

    /** The state the check read, which both variants report. */
    public EligibilityAssert<E, S, T> from(@NonNull S expected) {
        Eligibility<E, S, T> answer = Checks.subject(CONTEXT, "from", expected, actual);
        Checks.equal(CONTEXT, "from", expected, answer.from());
        return this;
    }

    /** The identifier the check asked about, which both variants report. */
    public EligibilityAssert<E, S, T> transition(@NonNull T expected) {
        Eligibility<E, S, T> answer = Checks.subject(CONTEXT, "transition", expected, actual);
        Checks.equal(CONTEXT, "transition", expected, answer.transition());
        return this;
    }

    /** The subject as its refusal, once it is known to be an ineligible answer, so the unwrapping cannot be empty. */
    private Eligibility.Ineligible<E, S, T> ineligible() {
        Eligibility<E, S, T> answer = Checks.subject(CONTEXT, VARIANT, INELIGIBLE, actual);
        Checks.equal(CONTEXT, VARIANT, INELIGIBLE, variantOf(answer));
        return answer.asIneligible().orElseThrow();
    }

    private static String variantOf(Eligibility<?, ?, ?> answer) {
        return answer.isEligible() ? ELIGIBLE : INELIGIBLE;
    }
}
