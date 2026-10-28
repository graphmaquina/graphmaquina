package io.github.graphmaquina.fixtures;

import io.github.graphmaquina.api.AutomaticStep;
import io.github.graphmaquina.api.Outcome;
import java.util.ArrayList;
import java.util.List;

/** The automatic steps of an outcome as texts that a test compares with literals. */
public final class StepTexts {

    private StepTexts() { }

    /**
     * One step as {@code Done <code>[ <identifier>] <recorded state>}, with the identifier of the edge the attempt
     * ran when it ran one, or as {@code Aborted <simple class name of the cause>}.
     */
    public static String describe(AutomaticStep step) {
        if (step instanceof AutomaticStep.Done done) {
            Outcome<?, ?, ?> outcome = done.outcome();
            String ran = outcome.requestedTransition().isPresent()
                    ? " " + outcome.requestedTransition().get().name() : "";
            return "Done " + outcome.code() + ran + " " + outcome.recordedState().name();
        }
        return "Aborted " + ((AutomaticStep.Aborted) step).cause().getClass().getSimpleName();
    }

    /** Each step as {@link #describe} writes it, in order. */
    public static List<String> described(List<AutomaticStep> steps) {
        List<String> texts = new ArrayList<>();
        for (AutomaticStep step : steps) {
            texts.add(describe(step));
        }
        return texts;
    }
}
