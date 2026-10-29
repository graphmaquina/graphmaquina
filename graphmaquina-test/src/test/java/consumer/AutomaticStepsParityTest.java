package consumer;

import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.graphmaquina.api.AutomaticStep;
import io.github.graphmaquina.api.Eligibility;
import io.github.graphmaquina.api.FlowView;
import io.github.graphmaquina.api.IgnoreReason;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.Reaction;
import io.github.graphmaquina.api.RejectionKind;
import io.github.graphmaquina.test.fixtures.TestDomain;
import io.github.graphmaquina.test.fixtures.TestDomain.Entity;
import io.github.graphmaquina.test.fixtures.TestDomain.Id;
import io.github.graphmaquina.test.fixtures.TestDomain.State;
import io.github.graphmaquina.test.internal.AutomaticSteps;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The keyed searches behind the assertions walk the steps in the very order of the core's searches, which they copy:
 * on trees whose steps are all of one machine, each keyed search answers the very step or outcome the core's keyless
 * one answers, the reactions searched before the steps, and a step's nested outcome before the next step.
 */
class AutomaticStepsParityTest {

    private static final Entity ENTITY = new Entity("e", State.READY);

    private static Outcome.Applied<Entity, State, Id> applied(List<Reaction> reactions, AutomaticStep... steps) {
        return new Outcome.Applied<>(ENTITY, State.NEW, Id.PREPARE, State.READY, List.of(), reactions, List.of(),
                FlowView.empty(), List.of(steps));
    }

    private static Outcome.Failed<Entity, State, Id> failed() {
        return new Outcome.Failed<>(ENTITY, State.READY, Id.FINISH, State.DONE, Id.RECOVER, State.FAILED, "boom",
                Optional.empty(), List.of(), List.of());
    }

    /** An attempt whose only candidate, FINISH, was refused by its guard {@code open}. */
    private static Outcome.Ignored<Entity, State, Id> blocked() {
        return new Outcome.Ignored<>(ENTITY, State.READY, IgnoreReason.NO_CANDIDATE_ADMITTED,
                List.of(new Eligibility.Ineligible<>(ENTITY, State.READY, Id.FINISH, "not yet", RejectionKind.GUARD,
                        Optional.of("open"))));
    }

    private static AutomaticStep.Done done(Outcome<?, ?, ?> outcome) {
        return new AutomaticStep.Done(TestDomain.KEY, ENTITY, outcome);
    }

    private static AutomaticStep.Aborted aborted(String why) {
        return new AutomaticStep.Aborted(TestDomain.KEY, ENTITY, new IllegalStateException(why));
    }

    /**
     * Asserts that each keyed search of {@code outcome} answers the very result of the keyless one, and that those
     * are {@code failure}, {@code abort} and {@code block}.
     */
    private static void sameAsTheCore(Outcome<?, ?, ?> outcome, Outcome.Failed<?, ?, ?> failure,
                                      AutomaticStep.Aborted abort, Outcome.Ignored<?, ?, ?> block) {
        assertSame(failure, outcome.automaticFailure().orElseThrow());
        assertSame(failure, AutomaticSteps.failure(outcome, TestDomain.KEY).orElseThrow());
        assertSame(abort, outcome.automaticAbort().orElseThrow());
        assertSame(abort, AutomaticSteps.abort(outcome, TestDomain.KEY).orElseThrow());
        assertSame(block, outcome.automaticBlock().orElseThrow());
        assertSame(block, AutomaticSteps.block(outcome, TestDomain.KEY).orElseThrow());
    }

    /** A step nested in a reaction ran before the commit's own steps, so both searches answer it first. */
    @Test
    void reactionsComeBeforeSteps() {
        Outcome.Failed<Entity, State, Id> inReaction = failed();
        AutomaticStep.Aborted abortedInReaction = aborted("in the reaction");
        Outcome.Ignored<Entity, State, Id> blockedInReaction = blocked();
        Outcome<Entity, State, Id> reacted = applied(List.of(), done(inReaction), abortedInReaction,
                done(blockedInReaction));
        Outcome<Entity, State, Id> outcome = applied(List.of(new Reaction.Done(TestDomain.KEY, ENTITY, reacted)),
                done(failed()), aborted("own"), done(blocked()));
        sameAsTheCore(outcome, inReaction, abortedInReaction, blockedInReaction);
    }

    /** The steps nested in a step's outcome ran before the next step, so both searches answer them first. */
    @Test
    void aStepsNestedOutcomeComesBeforeTheNextStep() {
        Outcome.Failed<Entity, State, Id> nested = failed();
        AutomaticStep.Aborted abortedNested = aborted("nested");
        Outcome.Ignored<Entity, State, Id> blockedNested = blocked();
        Outcome<Entity, State, Id> first = applied(List.of(), done(nested), abortedNested, done(blockedNested));
        Outcome<Entity, State, Id> outcome = applied(List.of(), done(first), done(failed()), aborted("next"),
                done(blocked()));
        sameAsTheCore(outcome, nested, abortedNested, blockedNested);
    }
}
