package io.github.graphmaquina.api;

import java.util.Optional;
import java.util.function.Function;

/**
 * The search behind {@link Outcome#automaticFailure()}, {@link Outcome#automaticAbort()} and
 * {@link Outcome#automaticBlock()}: the first automatic step of a call that matches, in the order the steps ran. That
 * order is the commit's reactions first, each {@link Reaction.Done} searched through its outcome the same way, then
 * the commit's automatic steps, each one looked at itself and then searched through its outcome. Only
 * {@link AutomaticStep} entries count: the result of a watch reaction never does.
 */
final class AutomaticSearch {

    private AutomaticSearch() { }

    /** The first step that ended in {@link Outcome.Failed}. */
    static Optional<Outcome.Failed<?, ?, ?>> failure(Outcome<?, ?, ?> outcome) {
        return first(outcome, (AutomaticStep step) ->
                step instanceof AutomaticStep.Done done && done.outcome() instanceof Outcome.Failed<?, ?, ?> failed
                        ? Optional.of(failed) : Optional.empty());
    }

    /** The first step that aborted. */
    static Optional<AutomaticStep.Aborted> abort(Outcome<?, ?, ?> outcome) {
        return first(outcome, (AutomaticStep step) ->
                step instanceof AutomaticStep.Aborted aborted ? Optional.of(aborted) : Optional.empty());
    }

    /** The first step that ended in {@link Outcome.Ignored} because no candidate was admitted. */
    static Optional<Outcome.Ignored<?, ?, ?>> block(Outcome<?, ?, ?> outcome) {
        return first(outcome, (AutomaticStep step) ->
                step instanceof AutomaticStep.Done done && done.outcome() instanceof Outcome.Ignored<?, ?, ?> ignored
                        && ignored.reason() == IgnoreReason.NO_CANDIDATE_ADMITTED
                        ? Optional.of(ignored) : Optional.empty());
    }

    /** What {@code pick} answers for the first step under {@code outcome}, in the order the class describes. */
    private static <R> Optional<R> first(Outcome<?, ?, ?> outcome, Function<AutomaticStep, Optional<R>> pick) {
        for (Reaction reaction : outcome.reactions()) {
            if (reaction instanceof Reaction.Done done) {
                Optional<R> found = first(done.outcome(), pick);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        for (AutomaticStep step : outcome.automaticSteps()) {
            Optional<R> found = pick.apply(step);
            if (found.isEmpty() && step instanceof AutomaticStep.Done done) {
                found = first(done.outcome(), pick);
            }
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }
}
