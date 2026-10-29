package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.StepTexts.described;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.AutomaticStep;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.error.TransitionAbortedException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.function.IntFunction;
import lombok.Getter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * An automatic attempt is a root call of its own, so it waits for an asynchronous action under a deadline of its own,
 * counted from its start from the machine's asyncTimeout, as a watch reaction does: a chain may take longer than one
 * deadline, and an attempt whose wait runs out aborts alone, the steps before it staying applied.
 */
class AutomaticChainAsyncTest {

    /** The legs of a relay, from the start S0 to the finish S4. */
    enum Leg { S0, S1, S2, S3, S4 }

    /** RUN_n leads from the leg before to Sn. */
    enum Run { RUN_1, RUN_2, RUN_3, RUN_4 }

    static final MachineKey<Runner, Leg, Run> RUNNERS = MachineKey.of(Runner.class, Leg.class, Run.class);

    /** A runner: an id and the leg it stands on. */
    @Getter
    static final class Runner {

        private final String id;
        private Leg leg = Leg.S0;

        Runner(String id) {
            this.id = id;
        }

        void setLeg(Leg leg) { this.leg = leg; }
    }

    private final ExecutorService virtual = Executors.newVirtualThreadPerTaskExecutor();

    /** The stage the action of RUN_n returns, given n. */
    private IntFunction<CompletableFuture<ActionOutcome>> stage =
            (int run) -> CompletableFuture.completedFuture(ActionOutcome.ok());

    @AfterEach
    void closeTheExecutor() {
        virtual.shutdownNow();
    }

    /**
     * The relay under {@code asyncTimeout}: RUN_1 from S0 is manual and synchronous, and RUN_2 to RUN_4 are automatic,
     * each with an asynchronous action that returns what {@link #stage} gives for its number.
     */
    private StateMachine<Runner, Leg, Run> relay(Duration asyncTimeout) {
        GraphDefinition<Runner, Leg, Run> graph = StateGraph.define(RUNNERS).initial(Leg.S0).terminal(Leg.S4)
                .statePolicy(Leg.S0, "a runner starts").statePolicy(Leg.S1, "a runner runs")
                .statePolicy(Leg.S2, "a runner runs").statePolicy(Leg.S3, "a runner runs")
                .transition(Run.RUN_1, Leg.S0, Leg.S1).endTransition()
                .transition(Run.RUN_2, Leg.S1, Leg.S2).automatic()
                    .asyncAction("run2", (TransitionContext<Runner, Leg, Run> ctx) -> stage.apply(2)).endTransition()
                .transition(Run.RUN_3, Leg.S2, Leg.S3).automatic()
                    .asyncAction("run3", (TransitionContext<Runner, Leg, Run> ctx) -> stage.apply(3)).endTransition()
                .transition(Run.RUN_4, Leg.S3, Leg.S4).automatic()
                    .asyncAction("run4", (TransitionContext<Runner, Leg, Run> ctx) -> stage.apply(4)).endTransition()
                .build();
        return StateMachines.from(graph).state(Runner::leg, Runner::setLeg).identity(Runner::id)
                .asyncTimeout(asyncTimeout).build();
    }

    /** A stage that completes OK on another thread once {@code millis} passed. */
    private CompletableFuture<ActionOutcome> after(long millis) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ActionOutcome.ok();
        }, virtual);
    }

    /**
     * Three attempts of 250 ms each under an asyncTimeout of 600 ms: the chain takes longer than one deadline, and
     * every attempt applies, because each one's deadline starts with it.
     */
    @Test
    void eachAttemptGetsItsOwnDeadline() {
        stage = (int run) -> after(250);
        Runner runner = new Runner("r1");
        long start = System.nanoTime();
        Outcome<Runner, Leg, Run> outcome = relay(Duration.ofMillis(600)).execute(runner, Run.RUN_1);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
        assertEquals(List.of("Done APPLIED RUN_2 S2", "Done APPLIED RUN_3 S3", "Done APPLIED RUN_4 S4"),
                described(outcome.automaticSteps()));
        assertTrue(elapsedMillis > 600, "took " + elapsedMillis + " ms");
        assertEquals(Leg.S4, runner.leg());
    }

    /**
     * The second attempt waits on a stage that never completes: once its own deadline passed it aborts, before its
     * commit, so the runner stays where the first attempt left it and the chain stops there.
     */
    @Test
    void aTimedOutAttemptIsAbortedAndEarlierStepsStay() {
        CompletableFuture<ActionOutcome> never = new CompletableFuture<>();
        stage = (int run) -> run == 3 ? never : CompletableFuture.completedFuture(ActionOutcome.ok());
        Runner runner = new Runner("r1");
        Outcome<Runner, Leg, Run> outcome = relay(Duration.ofMillis(300)).execute(runner, Run.RUN_1);
        assertEquals(List.of("Done APPLIED RUN_2 S2", "Aborted TransitionAbortedException"),
                described(outcome.automaticSteps()));
        AutomaticStep.Aborted aborted = outcome.automaticAbort().orElseThrow();
        assertSame(outcome.automaticSteps().get(1), aborted);
        TransitionAbortedException cause = assertInstanceOf(TransitionAbortedException.class, aborted.cause());
        assertInstanceOf(TimeoutException.class, cause.getCause());
        assertEquals(Leg.S2, runner.leg());
    }
}
