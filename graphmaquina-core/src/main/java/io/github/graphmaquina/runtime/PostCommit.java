package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.AutomaticStep;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.Eligibility;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.Reaction;
import io.github.graphmaquina.api.SubmachineStart;
import io.github.graphmaquina.api.TransitionRecord;
import java.util.List;
import java.util.Optional;

/**
 * What a root call's post-commit ran once its commit wrote something, the watch reactions of the commit and the
 * automatic steps of the chains it triggered, and the outcome that the call builds with them, as {@link RootCall}
 * runs the call.
 *
 * @param reactions the watch reactions of the commit
 * @param steps the automatic steps of the chains the commit triggered
 */
record PostCommit(List<Reaction> reactions, List<AutomaticStep> steps) {

    /**
     * What follows a commit that wrote something, {@code committed}: its line, the hooks of its hops, then, in the
     * phase REACTIONS, the watch reactions that {@code summary} triggers, one watch level above {@code levels}, then,
     * in the phase AUTOMATIC_STEPS, the automatic chains of the entities of the commit but {@code continuing}, as
     * {@link AutomaticChains#run} says. Nothing runs, and neither a reaction nor a step comes back, when nothing was
     * committed. A hook that throws ends the call before its watch dispatch and its chains.
     */
    static PostCommit after(Transaction tx, List<TransitionRecord<?, ?, ?>> records,
                            List<SubmachineStart<?, ?, ?>> starts, List<LogEntry> committed, CommitSummary summary,
                            ChainLevels levels, Optional<EntityRef> continuing) {
        if (committed.isEmpty()) {
            return new PostCommit(List.of(), List.of());
        }
        if (tx.log().debug()) {
            tx.log().committed(committed);
        }
        AfterCommitHooks.run(tx, records, starts, committed);
        tx.enter(CallPhase.REACTIONS);
        List<Reaction> reactions = tx.registry().dispatchWatches(summary, levels, tx.log());
        tx.enter(CallPhase.AUTOMATIC_STEPS);
        return new PostCommit(reactions, AutomaticChains.run(tx, summary, levels, continuing));
    }

    /**
     * The outcome of the call for its own step, {@code step}: the committed {@code records} and {@code starts}, these
     * reactions and automatic steps, and the call's flow frozen as the outcome hands it out; for an advance that
     * admitted no candidate, the rejections its body listed.
     */
    <E, S extends Enum<S>, T extends Enum<T>> Outcome<E, S, T> outcome(
            Transaction tx, E entity, StepResult<S, T> step, List<TransitionRecord<?, ?, ?>> records,
            List<SubmachineStart<?, ?, ?>> starts) {
        return switch (step) {
            case StepResult.Applied<S, T> applied ->
                    new Outcome.Applied<>(entity, applied.from(), applied.transition(), applied.to(),
                            records, reactions, starts, OutcomeFlows.frozenFlow(tx, records, starts), steps);
            case StepResult.Ignored<S, T> ignored ->
                    new Outcome.Ignored<>(entity, ignored.from(), ignored.reason(), rejectionsOf(ignored));
            case StepResult.Rejected<S, T> rejected ->
                    new Outcome.Rejected<>(entity, rejected.from(), rejected.transition(), rejected.reason(),
                            rejected.kind(), rejected.guard(), OutcomeFlows.frozenFlow(tx, rejected));
            case StepResult.Failed<S, T> failed ->
                    new Outcome.Failed<>(entity, failed.from(), failed.transition(), failed.to(),
                            failed.failureTransition(), failed.movedTo(), failed.reason(), failed.cause(),
                            records, reactions, failed.failureKind(), starts,
                            OutcomeFlows.frozenFlow(tx, records, starts), steps);
        };
    }

    /**
     * The rejections of {@code ignored} for the call's entity. The only cast to the machine's entity type of them:
     * the body of an advance lists rejections of the call's own entity only, which is an {@code E}.
     */
    @SuppressWarnings("unchecked")
    private static <E, S extends Enum<S>, T extends Enum<T>> List<Eligibility.Ineligible<E, S, T>> rejectionsOf(
            StepResult.Ignored<S, T> ignored) {
        return (List<Eligibility.Ineligible<E, S, T>>) (List<?>) ignored.rejections();
    }
}
