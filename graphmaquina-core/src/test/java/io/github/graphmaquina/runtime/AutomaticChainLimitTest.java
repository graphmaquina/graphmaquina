package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.StepTexts.described;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.AutomaticStep;
import io.github.graphmaquina.api.CallOrigin;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.StatesWritten;
import io.github.graphmaquina.dsl.GraphBuilder;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.error.AutomaticChainExceededException;
import io.github.graphmaquina.fixtures.GuardProbe;
import io.github.graphmaquina.fixtures.Probe;
import java.util.List;
import java.util.Optional;
import lombok.Getter;
import org.junit.jupiter.api.Test;

/**
 * maxAutomaticChain bounds how many attempts that commit may follow one another from a call of the application: the
 * k-th attempt of a chain is at level k, the first attempt of an advance at level 1. An attempt past the limit still
 * selects; it ends Ignored when it admits no candidate, and aborts once one is admitted, before its action runs and
 * without writing, with AutomaticChainExceededException. A ladder of automatic edges counts the levels.
 */
class AutomaticChainLimitTest {

    /** The rungs of a ladder, from the ground R0 to the top R12. */
    enum Rung { R0, R1, R2, R3, R4, R5, R6, R7, R8, R9, R10, R11, R12 }

    /** CLIMB_n leads from the rung below to Rn. */
    enum Climb {
        CLIMB_1, CLIMB_2, CLIMB_3, CLIMB_4, CLIMB_5, CLIMB_6, CLIMB_7, CLIMB_8, CLIMB_9, CLIMB_10, CLIMB_11, CLIMB_12
    }

    static final MachineKey<Climber, Rung, Climb> LADDERS = MachineKey.of(Climber.class, Rung.class, Climb.class);

    /** A climber: an id and the rung it stands on. */
    @Getter
    static final class Climber {

        private final String id;
        private Rung rung;

        Climber(String id, Rung rung) {
            this.id = id;
            this.rung = rung;
        }

        void setRung(Rung rung) { this.rung = rung; }
    }

    private final GuardProbe<Climber, Rung, Climb> top = new GuardProbe<>();
    private final Probe<Climber, Rung, Climb> reach = new Probe<>();

    /**
     * The ladder: CLIMB_1 from R0 is manual, and every other climb is automatic; the last one, CLIMB_12 from R11, is
     * guarded by {@link #top} and runs {@link #reach}. R12 is terminal.
     */
    private GraphDefinition<Climber, Rung, Climb> ladder() {
        GraphBuilder<Climber, Rung, Climb> graph = StateGraph.define(LADDERS).initial(Rung.R0).terminal(Rung.R12)
                .transition(Climb.CLIMB_1, Rung.R0, Rung.R1).endTransition();
        for (int rung = 0; rung < 12; rung++) {
            graph = graph.statePolicy(Rung.values()[rung], "a climber climbs");
        }
        for (int rung = 2; rung < 12; rung++) {
            graph = graph.transition(Climb.values()[rung - 1], Rung.values()[rung - 1], Rung.values()[rung])
                    .automatic().endTransition();
        }
        return graph.transition(Climb.CLIMB_12, Rung.R11, Rung.R12).automatic().guard("top", top)
                .action("reach", reach).endTransition()
                .build();
    }

    /** The ladder in a registry under {@code maxAutomaticChain}. */
    private StateMachine<Climber, Rung, Climb> ladder(int maxAutomaticChain) {
        return MachineRegistry.builder()
                .maxAutomaticChain(maxAutomaticChain)
                .add(StateMachines.from(ladder()).state(Climber::rung, Climber::setRung).identity(Climber::id))
                .build()
                .of(LADDERS);
    }

    private void closeTheTop() {
        top.verdict = (GuardContext<Climber, Rung, Climb> ctx) -> GuardResult.reject("the top is closed");
    }

    /** The cause of the last step of {@code outcome}, which aborted on the limit. */
    private static AutomaticChainExceededException exceeded(Outcome<Climber, Rung, Climb> outcome) {
        List<AutomaticStep> steps = outcome.automaticSteps();
        AutomaticStep.Aborted aborted = assertInstanceOf(AutomaticStep.Aborted.class, steps.get(steps.size() - 1));
        return assertInstanceOf(AutomaticChainExceededException.class, aborted.cause());
    }

    /**
     * A chain of exactly maxAutomaticChain attempts runs them all; the next attempt still selects, and ends Ignored
     * when it admits nothing.
     */
    @Test
    void exactlyTheLimitDoesNotAbort() {
        StateMachine<Climber, Rung, Climb> ladder = ladder(3);
        Climber reached = new Climber("c1", Rung.R8);
        assertEquals(List.of("Done APPLIED CLIMB_10 R10", "Done APPLIED CLIMB_11 R11", "Done APPLIED CLIMB_12 R12"),
                described(ladder.execute(reached, Climb.CLIMB_9).automaticSteps()));
        assertEquals(Rung.R12, reached.rung());
        closeTheTop();
        Climber blocked = new Climber("c2", Rung.R7);
        assertEquals(List.of("Done APPLIED CLIMB_9 R9", "Done APPLIED CLIMB_10 R10", "Done APPLIED CLIMB_11 R11",
                "Done NO_CANDIDATE_ADMITTED R11"), described(ladder.execute(blocked, Climb.CLIMB_8).automaticSteps()));
        assertEquals(Rung.R11, blocked.rung());
        assertEquals(2, top.calls);
    }

    /**
     * The attempt one past the limit runs the guards of its candidate, then aborts before its action, writing
     * nothing: the climber stays where the last attempt left it.
     */
    @Test
    void limitPlusOneAbortsWithoutWriting() {
        Climber climber = new Climber("c1", Rung.R7);
        Outcome<Climber, Rung, Climb> outcome = ladder(3).execute(climber, Climb.CLIMB_8);
        assertEquals(List.of("Done APPLIED CLIMB_9 R9", "Done APPLIED CLIMB_10 R10", "Done APPLIED CLIMB_11 R11",
                "Aborted AutomaticChainExceededException"), described(outcome.automaticSteps()));
        AutomaticChainExceededException cause = exceeded(outcome);
        assertEquals(3, cause.maxChain());
        assertEquals(List.of(CallPhase.GUARD, StatesWritten.NONE, CallOrigin.AUTOMATIC_STEP),
                List.of(cause.phase(), cause.statesWritten(), cause.call().origin()));
        assertEquals(List.of(Optional.of(Rung.R11), Optional.of(Climb.CLIMB_12), Optional.of(Rung.R12)),
                List.of(cause.from(), cause.transition(), cause.to()));
        assertEquals("transition aborted in Climber/Rung R11 -CLIMB_12-> R12: the chain of automatic attempts exceeds"
                + " maxAutomaticChain = 3 [route Climber/Rung#c1]", cause.getMessage());
        assertEquals(1, top.calls);
        assertEquals(List.of(), reach.calls);
        assertEquals(Rung.R11, climber.rung());
    }

    /**
     * A candidate without guards is admitted while the call is still in RESOLVE, so the attempt one past the limit
     * aborts in RESOLVE, before CLIMB_11 runs and without writing.
     */
    @Test
    void limitPlusOneOnACandidateWithoutGuardsAbortsInResolve() {
        Climber climber = new Climber("c1", Rung.R6);
        Outcome<Climber, Rung, Climb> outcome = ladder(3).execute(climber, Climb.CLIMB_7);
        assertEquals(List.of("Done APPLIED CLIMB_8 R8", "Done APPLIED CLIMB_9 R9", "Done APPLIED CLIMB_10 R10",
                "Aborted AutomaticChainExceededException"), described(outcome.automaticSteps()));
        AutomaticChainExceededException cause = exceeded(outcome);
        assertEquals(List.of(CallPhase.RESOLVE, StatesWritten.NONE, CallOrigin.AUTOMATIC_STEP),
                List.of(cause.phase(), cause.statesWritten(), cause.call().origin()));
        assertEquals(List.of(Optional.of(Rung.R10), Optional.of(Climb.CLIMB_11), Optional.of(Rung.R11)),
                List.of(cause.from(), cause.transition(), cause.to()));
        assertEquals(Rung.R10, climber.rung());
    }

    /** The first attempt of an advance is at level 1, so its chain reaches the limit one attempt sooner. */
    @Test
    void theFirstAttemptOfAdvanceCounts() {
        Climber climber = new Climber("c1", Rung.R8);
        Outcome<Climber, Rung, Climb> outcome = ladder(3).advance(climber);
        assertEquals(Optional.of(Climb.CLIMB_9), outcome.requestedTransition());
        assertEquals(List.of("Done APPLIED CLIMB_10 R10", "Done APPLIED CLIMB_11 R11",
                "Aborted AutomaticChainExceededException"), described(outcome.automaticSteps()));
        assertEquals(Rung.R11, climber.rung());
    }

    @Test
    void valuesBelowOneAreRefused() {
        for (int value : List.of(0, -1)) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> MachineRegistry.builder().maxAutomaticChain(value));
            assertEquals("maxAutomaticChain must be greater than or equal to 1", refused.getMessage());
        }
        assertEquals(List.of("Done APPLIED CLIMB_12 R12"),
                described(ladder(1).execute(new Climber("c1", Rung.R10), Climb.CLIMB_11).automaticSteps()));
    }

    /** A machine built without a registry allows ten attempts in a chain, as a registry does by default. */
    @Test
    void aStandaloneMachineUsesTen() {
        StateMachine<Climber, Rung, Climb> ladder =
                StateMachines.from(ladder()).state(Climber::rung, Climber::setRung).identity(Climber::id).build();
        Climber climber = new Climber("c1", Rung.R0);
        Outcome<Climber, Rung, Climb> outcome = ladder.execute(climber, Climb.CLIMB_1);
        assertEquals(11, outcome.automaticSteps().size());
        assertEquals("Done APPLIED CLIMB_11 R11", described(outcome.automaticSteps()).get(9));
        assertEquals(10, exceeded(outcome).maxChain());
        assertEquals(Rung.R11, climber.rung());
    }
}
