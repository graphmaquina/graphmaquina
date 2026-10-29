package io.github.graphmaquina.test.internal;

import io.github.graphmaquina.api.AutomaticStep;
import io.github.graphmaquina.api.IgnoreReason;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.Reaction;
import java.util.Optional;
import java.util.function.Function;
import lombok.NonNull;

/**
 * The searches of {@link Outcome#automaticFailure()}, {@link Outcome#automaticAbort()} and
 * {@link Outcome#automaticBlock()}, restricted to the steps of one machine. They walk the steps in the very order the
 * core searches do: first the outcome's reactions, each {@link Reaction.Done} searched through its own outcome the same
 * way, then each step of the outcome, looked at itself and then searched through its outcome. Where the core takes the
 * first match of any machine, these take the first whose {@link AutomaticStep#key()} equals the key given, so a match
 * of a peer's chain that comes first does not hide the machine's own.
 */
public final class AutomaticSteps {

    private AutomaticSteps() { }

    /** The first step of {@code key} that is done with a failed outcome. */
    public static Optional<Outcome.Failed<?, ?, ?>> failure(
            @NonNull Outcome<?, ?, ?> outcome, @NonNull MachineKey<?, ?, ?> key) {
        return first(outcome, (AutomaticStep step) ->
                step.key().equals(key) && step instanceof AutomaticStep.Done done
                        && done.outcome() instanceof Outcome.Failed<?, ?, ?> failed
                        ? Optional.of(failed) : Optional.empty());
    }

    /** The first step of {@code key} that is done with an ignored outcome because no candidate was admitted. */
    public static Optional<Outcome.Ignored<?, ?, ?>> block(
            @NonNull Outcome<?, ?, ?> outcome, @NonNull MachineKey<?, ?, ?> key) {
        return first(outcome, (AutomaticStep step) ->
                step.key().equals(key) && step instanceof AutomaticStep.Done done
                        && done.outcome() instanceof Outcome.Ignored<?, ?, ?> ignored
                        && ignored.reason() == IgnoreReason.NO_CANDIDATE_ADMITTED
                        ? Optional.of(ignored) : Optional.empty());
    }

    /** The first step of {@code key} that aborted. */
    public static Optional<AutomaticStep.Aborted> abort(
            @NonNull Outcome<?, ?, ?> outcome, @NonNull MachineKey<?, ?, ?> key) {
        return first(outcome, (AutomaticStep step) ->
                step.key().equals(key) && step instanceof AutomaticStep.Aborted aborted
                        ? Optional.of(aborted) : Optional.empty());
    }

    /**
     * The key of the machine whose done step holds {@code attempt}, compared by identity, wherever it sits under
     * {@code outcome}; empty when no step holds it.
     */
    public static Optional<MachineKey<?, ?, ?>> keyOf(
            @NonNull Outcome<?, ?, ?> outcome, @NonNull Outcome<?, ?, ?> attempt) {
        return first(outcome, (AutomaticStep step) ->
                step instanceof AutomaticStep.Done done && done.outcome() == attempt
                        ? Optional.of(step.key()) : Optional.empty());
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
