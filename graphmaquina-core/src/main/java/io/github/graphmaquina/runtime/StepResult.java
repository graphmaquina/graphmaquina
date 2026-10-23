package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.FailureKind;
import io.github.graphmaquina.api.FlowView;
import io.github.graphmaquina.api.IgnoreReason;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.RejectionKind;
import java.util.List;
import java.util.Optional;

/**
 * Internal outcome of a step. The root turns it into an Outcome once its commit, if any, its hooks and its reactions
 * are done; a cascade turns it into one at once, as {@link #outcomeOf} says.
 */
sealed interface StepResult<S extends Enum<S>, T extends Enum<T>>
        permits StepResult.Applied, StepResult.Ignored, StepResult.Rejected, StepResult.Failed {

    S from();

    /**
     * Adapts a nested step to an outcome: records, reactions, starts and the flow belong to the root, so the lists stay
     * empty here and the flow is {@link FlowView#empty()}, which the constructors without it give.
     */
    static <P, Q extends Enum<Q>, U extends Enum<U>> Outcome<P, Q, U> outcomeOf(P peer, StepResult<Q, U> step) {
        return switch (step) {
            case StepResult.Applied<Q, U> applied ->
                    new Outcome.Applied<>(peer, applied.from(), applied.transition(), applied.to(), List.of(),
                            List.of());
            // Unreachable: a nested step executes an edge and never reevaluates. Kept only for switch exhaustiveness.
            case StepResult.Ignored<Q, U> ignored -> new Outcome.Ignored<>(peer, ignored.from(), ignored.reason());
            // Unreachable: at depth > 0, Steps.rejected(...) always aborts. Kept only for switch exhaustiveness.
            case StepResult.Rejected<Q, U> rejected -> new Outcome.Rejected<>(peer, rejected.from(),
                    rejected.transition(), rejected.reason(), rejected.kind(), rejected.guard());
            case StepResult.Failed<Q, U> failed -> new Outcome.Failed<>(peer, failed.from(), failed.transition(),
                    failed.to(), failed.failureTransition(), failed.movedTo(), failed.reason(), failed.cause(),
                    List.of(), List.of(), failed.failureKind());
        };
    }

    /** The selected edge ran and is pending commit. */
    record Applied<S extends Enum<S>, T extends Enum<T>>(S from, T transition, S to) implements StepResult<S, T> { }

    /** A reevaluation that selected no edge. No step builds it: reevaluate returns Outcome.Ignored without one. */
    record Ignored<S extends Enum<S>, T extends Enum<T>>(S from, IgnoreReason reason) implements StepResult<S, T> { }

    /**
     * The step was refused for the reason {@code kind} names: no edge with that identifier leaves {@code from}, a
     * guard rejected, the entity is a sub-machine whose parent does not hold it, or the identifier is an exit of the
     * sub-machine bound to {@code from}. No entry was added to the unit of work's log and no action ran, though on a
     * guard rejection the guards up to the rejecting one did. Only a root step returns it; a deeper one aborts instead.
     */
    record Rejected<S extends Enum<S>, T extends Enum<T>>(S from, T transition, String reason, RejectionKind kind,
                                                          Optional<String> guard) implements StepResult<S, T> { }

    /**
     * Domain-level failure that took the recovery edge {@code failureTransition} to {@code movedTo}, with the failure
     * kind the outcome reports.
     */
    record Failed<S extends Enum<S>, T extends Enum<T>>(S from, T transition, S to, T failureTransition, S movedTo,
                                                        String reason, Optional<Throwable> cause,
                                                        Optional<Class<? extends FailureKind>> failureKind)
            implements StepResult<S, T> { }
}
