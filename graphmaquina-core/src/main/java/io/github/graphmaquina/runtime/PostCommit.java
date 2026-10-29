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
 * automatic steps of the chains it triggered, the entities that calls nested in it committed, and the outcome that
 * the call builds with them, as {@link RootCall} runs the call.
 *
 * @param reactions the watch reactions of the commit
 * @param steps the automatic steps of the chains the commit triggered
 * @param movedBelow the entities that the commits nested in the post-commit reported, as
 *     {@link PostCommitScope#moved()} lists them; empty when the registry has no automatic edge, which opens no
 *     scope
 */
record PostCommit(List<Reaction> reactions, List<AutomaticStep> steps, List<EntityRef> movedBelow) {

    /**
     * What follows a commit that wrote something, {@code committed}: it reports its entities to the post-commits this
     * thread runs around it and logs its line; then the hooks of its hops, then, in the phase REACTIONS, the watch
     * reactions that {@code summary} triggers, one watch level above {@code levels}. In a registry with an automatic
     * edge, its own {@link PostCommitScope} is open around them, and around the automatic chains that follow, in the
     * phase AUTOMATIC_STEPS, of the entities of the commit but {@code continuing}, as {@link AutomaticChains#run}
     * says; the scope closes whatever they throw. A registry without one opens no scope, runs no chain and never
     * enters AUTOMATIC_STEPS, and no nested call's entity comes back. Nothing runs, and neither a reaction nor a step
     * comes back, when nothing was committed. A hook that throws ends the call before its watch dispatch and its
     * chains.
     */
    static PostCommit after(Transaction tx, List<TransitionRecord<?, ?, ?>> records,
                            List<SubmachineStart<?, ?, ?>> starts, List<LogEntry> committed, CommitSummary summary,
                            ChainLevels levels, Optional<EntityRef> continuing) {
        if (committed.isEmpty()) {
            return new PostCommit(List.of(), List.of(), List.of());
        }
        if (PostCommitScope.anyOpen()) {
            PostCommitScope.report(summary.refs());
        }
        if (tx.log().debug()) {
            tx.log().committed(committed);
        }
        if (!tx.registry().hasAutomaticEdges()) {
            return new PostCommit(hooksAndReactions(tx, records, starts, committed, summary, levels), List.of(),
                    List.of());
        }
        List<Reaction> reactions;
        List<AutomaticStep> steps;
        PostCommitScope scope = PostCommitScope.open();
        try {
            reactions = hooksAndReactions(tx, records, starts, committed, summary, levels);
            tx.enter(CallPhase.AUTOMATIC_STEPS);
            steps = AutomaticChains.run(tx, summary, levels, continuing, scope);
        } finally {
            scope.closed = true;
            scope.close();
        }
        return new PostCommit(reactions, steps, scope.moved());
    }

    /**
     * The hooks of the hops of {@code committed}, then, in the phase REACTIONS, the watch reactions that
     * {@code summary} triggers, one watch level above {@code levels}, which it returns.
     */
    private static List<Reaction> hooksAndReactions(Transaction tx, List<TransitionRecord<?, ?, ?>> records,
                                                    List<SubmachineStart<?, ?, ?>> starts, List<LogEntry> committed,
                                                    CommitSummary summary, ChainLevels levels) {
        AfterCommitHooks.run(tx, records, starts, committed);
        tx.enter(CallPhase.REACTIONS);
        return tx.registry().dispatchWatches(summary, levels, tx.log());
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
