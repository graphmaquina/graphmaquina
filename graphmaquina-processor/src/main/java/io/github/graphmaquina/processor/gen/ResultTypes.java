package io.github.graphmaquina.processor.gen;

import io.github.graphmaquina.api.ChoiceEligibility;
import io.github.graphmaquina.api.Outcome;

/**
 * The names of the result types of a facade's entry points, declared once for the processor: its checks read the
 * declared results against them, and the companion writes them.
 */
public final class ResultTypes {

    /** {@link Outcome}: the result of an operation, of a choice that runs, and of the companion's calls. */
    public static final String OUTCOME = Outcome.class.getName();

    /** {@link ChoiceEligibility}: the result of a choice that checks without running. */
    public static final String CHOICE_ELIGIBILITY = ChoiceEligibility.class.getName();

    private ResultTypes() {
    }
}
