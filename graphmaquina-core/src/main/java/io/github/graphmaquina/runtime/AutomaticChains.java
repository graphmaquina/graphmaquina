package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.AutomaticStep;
import io.github.graphmaquina.api.CallOrigin;
import io.github.graphmaquina.api.Outcome;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The automatic chains that a root call's commit triggers, which its post-commit runs in the phase AUTOMATIC_STEPS
 * once its watch reactions ran: one chain per entity of the commit, each a loop of automatic attempts, each attempt a
 * root call of its own, as {@link Attempts} makes it.
 */
final class AutomaticChains {

    private AutomaticChains() { }

    /**
     * The chain of each entity that {@code summary} lists, in its order, the steps of each chain whole and in order:
     * every entity with a hop or a start in the commit, a self-loop or a round trip included, but the one whose chain
     * made this call an attempt of it, {@code continuing}, whose loop goes on with it, and those whose last entry is a
     * failure route that stops the chain. Each chain starts from the state its entity's last entry leaves it in, read
     * from the commit and not from the entity, and its attempts are one automatic level after another above
     * {@code levels}, at its watch level; each names the id of the call whose commit ran this, when that call logs, as
     * its trigger.
     */
    static List<AutomaticStep> run(Transaction tx, CommitSummary summary, ChainLevels levels,
                                   Optional<EntityRef> continuing) {
        Optional<Long> trigger = tx.log().id() > 0 ? Optional.of(tx.log().id()) : Optional.empty();
        List<AutomaticStep> steps = new ArrayList<>();
        for (ChainStart start : summary.chainStarts()) {
            if (start.stopsChain() || continuing.isPresent() && isContinuing(continuing.get(), start.ref())) {
                continue;
            }
            chain(tx.registry().anyMachineFor(start.ref().key()), start, levels, trigger, steps);
        }
        return List.copyOf(steps);
    }

    /**
     * Whether {@code ref}, an entity of this commit, is {@code continuing}, the entity of the chain that made this call
     * an attempt of it: the same machine and the very instance, which a commit registers once, so no identity's equals
     * runs here, outside any attempt, where nothing could turn what it throws into an aborted step.
     */
    private static boolean isContinuing(EntityRef continuing, EntityRef ref) {
        return continuing.instance() == ref.instance() && continuing.key().equals(ref.key());
    }

    /**
     * The chain of the entity of {@code start}, added to {@code steps}: while its state has an automatic edge, an
     * attempt, one automatic level above the one before it, listed as a {@link AutomaticStep.Done} with its outcome.
     * Without an automatic edge, it makes no attempt and runs no code of the application. The chain stops after an
     * attempt that ended Ignored, after one whose commit took a failure route that stops the chain, and after one that
     * threw anything but an Error, which is listed as {@link AutomaticStep.Aborted}, as is anything but an Error that
     * the graph's automaticFrom throws; an Error propagates. Otherwise it goes on from the state the attempt's commit
     * left the entity in.
     */
    private static <E, S extends Enum<S>, T extends Enum<T>> void chain(
            DefaultStateMachine<E, S, T> machine, ChainStart start, ChainLevels levels, Optional<Long> trigger,
            List<AutomaticStep> steps) {
        E entity = machine.key().entity().cast(start.ref().instance());
        S state = machine.key().states().cast(start.state());
        int level = levels.automatic();
        try {
            while (!machine.definition().automaticFrom(state).isEmpty()) {
                level++;
                Attempt<E, S, T> attempt = Attempts.attempt(machine, entity, CallOrigin.AUTOMATIC_STEP, trigger,
                        new ChainLevels(levels.watch(), level), Optional.of(start.ref()));
                steps.add(new AutomaticStep.Done(machine.key(), entity, attempt.outcome()));
                if (attempt.outcome() instanceof Outcome.Ignored<E, S, T> || attempt.stopsChain()) {
                    return;
                }
                state = attempt.state();
            }
        } catch (RuntimeException aborted) {
            steps.add(new AutomaticStep.Aborted(machine.key(), entity, aborted));
        }
    }
}
