package io.github.graphmaquina.test;

import io.github.graphmaquina.api.ChoiceEligibility;
import io.github.graphmaquina.test.internal.Checks;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Chainable, typed assertion over a {@link ChoiceEligibility}, the answer of a choice check: which of the caller's
 * candidates a choice would run now. Nothing ran and nothing was committed. One view serves both variants and every
 * expectation returns it, so a chain keeps its types.
 *
 * <p>{@link #chose(Enum)} asserts that the answer is {@code Chosen} and names that candidate, and
 * {@link #admittedNone()} that it is {@code NoneAdmitted}: each fails as an assertion on the variant on the other
 * one, never with a cast. {@link #from(Enum)} reads the state that both variants report, and {@link #rejections()} the
 * refusals that both hold, each read with the view of an ineligible answer: those of the candidates before the one
 * chosen, its {@code rejected}, empty when the first candidate is admitted, or those of every candidate, its
 * {@code rejections}.
 *
 * <p>A failure names the view {@code choice eligibility}, and a failure on a refusal names the component it was read
 * from. A null subject reaches the view untouched and fails, inspectably, on the first expectation called on it.
 *
 * @param <E> entity type of the machine
 * @param <S> state enum of the machine
 * @param <T> transition identifier enum of the machine
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class ChoiceEligibilityAssert<E, S extends Enum<S>, T extends Enum<T>> {

    private static final String CONTEXT = "choice eligibility";
    private static final String VARIANT = "variant";
    private static final String CHOSEN = "Chosen";
    private static final String NONE_ADMITTED = "NoneAdmitted";

    private final ChoiceEligibility<E, S, T> actual;

    /** The view over {@code actual}, which may be null and then fails on the first expectation called on it. */
    public static <E, S extends Enum<S>, T extends Enum<T>> ChoiceEligibilityAssert<E, S, T> assertThat(
            ChoiceEligibility<E, S, T> actual) {
        return new ChoiceEligibilityAssert<>(actual);
    }

    /** A candidate was admitted, and it is {@code expected}: the one the choice would run. */
    public ChoiceEligibilityAssert<E, S, T> chose(@NonNull T expected) {
        ChoiceEligibility<E, S, T> answer = Checks.subject(CONTEXT, VARIANT, CHOSEN, actual);
        Checks.equal(CONTEXT, VARIANT, CHOSEN, variantOf(answer));
        Checks.equal(CONTEXT, "chosen", expected, answer.chosen().orElseThrow());
        return this;
    }

    /** Every candidate was refused, so the choice would run none. */
    public ChoiceEligibilityAssert<E, S, T> admittedNone() {
        ChoiceEligibility<E, S, T> answer = Checks.subject(CONTEXT, VARIANT, NONE_ADMITTED, actual);
        Checks.equal(CONTEXT, VARIANT, NONE_ADMITTED, variantOf(answer));
        return this;
    }

    /** The state the check read, which both variants report: that of the first candidate when none is admitted. */
    public ChoiceEligibilityAssert<E, S, T> from(@NonNull S expected) {
        ChoiceEligibility<E, S, T> answer = Checks.subject(CONTEXT, "from", expected, actual);
        Checks.equal(CONTEXT, "from", expected, answer.from());
        return this;
    }

    /**
     * The refusals the answer holds, in the caller's order: the {@code rejected} of a {@code Chosen} answer, the
     * candidates refused before the one chosen, or the {@code rejections} of a {@code NoneAdmitted} one, every
     * candidate's.
     */
    public RejectionsAssert<E, S, T> rejections() {
        ChoiceEligibility<E, S, T> answer = Checks.subject(CONTEXT, "rejections", "an answer to read", actual);
        Optional<ChoiceEligibility.Chosen<E, S, T>> chosen = answer.asChosen();
        return chosen.isPresent()
                ? new RejectionsAssert<>(CONTEXT + ".rejected", chosen.get().rejected())
                : new RejectionsAssert<>(CONTEXT + ".rejections", answer.asNoneAdmitted().orElseThrow().rejections());
    }

    private static String variantOf(ChoiceEligibility<?, ?, ?> answer) {
        return answer.chosen().isPresent() ? CHOSEN : NONE_ADMITTED;
    }
}
