package io.github.graphmaquina.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.StatePolicy;
import io.github.graphmaquina.api.SubmachineSpec;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.api.WatchSpec;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.error.CascadeDepthExceededException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.graph.DirectedGraph;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;

/**
 * A failure route that takes a sub-machine along: a recovery edge that enters a bound state, and a recovery that ends a
 * held child in a terminal state. Every abort the engine builds for that recovering step, because the sub-machine its
 * recovery hop enters cannot start, or the exit that a terminal state selects, the started child's or the recovered
 * child's own, cannot run, holds as suppressed, by identity, the exception that selected the route, which the call
 * would otherwise lose.
 */
class SubmachineRecoveryTest {

    /** States of a job, which waits and ends done or failed. */
    enum JobState { WAITING, DONE, FAILED }

    /** Identifiers of the job machine: RUN fails over to FAIL, and KICK runs the job it is given. */
    enum JobChange { RUN, FAIL, KICK }

    /** States of a batch, which works through its job in WORKING and is sealed in SEALING. */
    enum BatchState { OPEN, WORKING, SEALING, COMPLETE }

    /**
     * Identifiers of the batch machine: START fails over to RETRY, which enters WORKING, whose exit is END; SEAL fails
     * over to RESEAL, which enters SEALING, whose exit is SEALED.
     */
    enum BatchChange { START, RETRY, END, SEAL, RESEAL, SEALED }

    /** The one state of a batch's seal, initial and terminal at once. */
    enum SealState { DONE }

    /** Identifiers of the seal machine, which has no edge. */
    enum SealChange { NONE }

    static final MachineKey<Job, JobState, JobChange> JOBS =
            MachineKey.of(Job.class, JobState.class, JobChange.class);
    static final MachineKey<Batch, BatchState, BatchChange> BATCHES =
            MachineKey.of(Batch.class, BatchState.class, BatchChange.class);
    static final MachineKey<Batch, SealState, SealChange> SEALS =
            MachineKey.of(Batch.class, SealState.class, SealChange.class);

    /** A job: its id and its state. */
    @Getter(AccessLevel.PACKAGE)
    @AllArgsConstructor(access = AccessLevel.PACKAGE)
    static final class Job {

        private final String id;
        private JobState state;

        void setState(JobState state) { this.state = state; }

        @Override
        public String toString() { return "Job#" + id; }
    }

    /** A batch: its id, its state, the state of its seal and the job it works through, if any. */
    static final class Batch {

        @Getter(AccessLevel.PACKAGE)
        private final String id;
        @Getter(AccessLevel.PACKAGE)
        private BatchState state;
        @Getter(AccessLevel.PACKAGE)
        private SealState seal = SealState.DONE;
        private Optional<Job> job = Optional.empty();

        Batch(String id, BatchState state) {
            this.id = id;
            this.state = state;
        }

        void setState(BatchState state) { this.state = state; }

        void setSeal(SealState seal) { this.seal = seal; }

        @Override
        public String toString() { return "Batch#" + id; }
    }

    /** What the actions of RUN and START throw, and what selects the route of each. */
    private final IllegalStateException original = new IllegalStateException("the scheduler is down");
    /** The batches that parentOf searches. */
    private final List<Batch> batches = new ArrayList<>();
    /** What the binding of WORKING reaches from a batch: the batch's own job unless replaced. */
    private Function<Batch, Optional<Job>> jobOf = (Batch batch) -> batch.job;
    private final GraphDefinition<Job, JobState, JobChange> jobs = jobs();
    private final GraphDefinition<Batch, SealState, SealChange> seals =
            StateGraph.define(SEALS).initial(SealState.DONE).terminal(SealState.DONE).build();
    private final GraphDefinition<Batch, BatchState, BatchChange> batchGraph = batchGraph();

    /** An action that throws {@link #original}. */
    private <E, S extends Enum<S>, T extends Enum<T>> TransitionAction<E, S, T> failing() {
        return (TransitionContext<E, S, T> ctx) -> {
            throw original;
        };
    }

    /** RUN throws {@link #original}, which its failsOn routes to FAIL; KICK runs RUN on the job its input names. */
    private GraphDefinition<Job, JobState, JobChange> jobs() {
        return StateGraph.define(JOBS).initial(JobState.WAITING).terminal(JobState.DONE).terminal(JobState.FAILED)
                .statePolicy(JobState.WAITING, "a job runs, fails or kicks another")
                .transition(JobChange.RUN, JobState.WAITING, JobState.DONE).action("Run", failing())
                    .failsOn(IllegalStateException.class).onFailure(JobChange.FAIL).endTransition()
                .transition(JobChange.FAIL, JobState.WAITING, JobState.FAILED).endTransition()
                .transition(JobChange.KICK, JobState.WAITING, JobState.DONE)
                    .action("Kick", (TransitionContext<Job, JobState, JobChange> ctx) -> {
                        ctx.cascade(JOBS, ctx.input(Job.class).orElseThrow(), JobChange.RUN);
                        return ActionOutcome.ok();
                    }).endTransition()
                .build();
    }

    /**
     * START throws {@link #original}, which its failsOn routes to RETRY; RETRY enters WORKING, which binds the job
     * {@link #jobOf} reaches, and both terminals of the job select END. SEAL throws it too, and its failsOn routes it
     * to RESEAL, which enters SEALING, which binds the batch's seal, terminal as soon as it starts, whose DONE selects
     * SEALED.
     */
    private GraphDefinition<Batch, BatchState, BatchChange> batchGraph() {
        return StateGraph.define(BATCHES).initial(BatchState.OPEN).terminal(BatchState.COMPLETE)
                .statePolicy(BatchState.OPEN, "a batch starts or is sealed")
                .statePolicy(BatchState.WORKING, "a batch ends").statePolicy(BatchState.SEALING, "a batch is sealed")
                .transition(BatchChange.START, BatchState.OPEN, BatchState.COMPLETE).action("Start", failing())
                    .failsOn(IllegalStateException.class).onFailure(BatchChange.RETRY).endTransition()
                .transition(BatchChange.RETRY, BatchState.OPEN, BatchState.WORKING).endTransition()
                .transition(BatchChange.END, BatchState.WORKING, BatchState.COMPLETE).endTransition()
                .transition(BatchChange.SEAL, BatchState.OPEN, BatchState.COMPLETE).action("Seal", failing())
                    .failsOn(IllegalStateException.class).onFailure(BatchChange.RESEAL).endTransition()
                .transition(BatchChange.RESEAL, BatchState.OPEN, BatchState.SEALING).endTransition()
                .transition(BatchChange.SEALED, BatchState.SEALING, BatchState.COMPLETE).endTransition()
                .submachine(BatchState.SEALING, seals)
                    .exit(SealState.DONE, BatchChange.SEALED)
                    .endSubmachine()
                .submachine(BatchState.WORKING, jobs, (Batch batch) -> jobOf.apply(batch),
                        (Job job) -> batches.stream()
                                .filter((Batch batch) -> batch.job.equals(Optional.of(job))).findFirst())
                    .exit(JobState.DONE, BatchChange.END)
                    .exit(JobState.FAILED, BatchChange.END)
                    .endSubmachine()
                .build();
    }

    /**
     * The job machine, the seal machine and the batch machine over {@code batchGraph}, with cascades at most
     * {@code depth} deep.
     */
    private MachineRegistry registry(GraphDefinition<Batch, BatchState, BatchChange> batchGraph, int depth) {
        return MachineRegistry.builder().maxCascadeDepth(depth)
                .add(StateMachines.from(jobs).state(Job::state, Job::setState).identity(Job::id))
                .add(StateMachines.from(seals).state(Batch::seal, Batch::setSeal).identity(Batch::id))
                .add(StateMachines.from(batchGraph).state(Batch::state, Batch::setState).identity(Batch::id))
                .build();
    }

    /** A batch in WORKING that holds {@code job}, which parentOf finds. */
    private Batch holding(Job job) {
        Batch batch = new Batch("b1", BatchState.WORKING);
        batch.job = Optional.of(job);
        batches.add(batch);
        return batch;
    }

    /**
     * The recovery hop RETRY enters WORKING, whose job cannot start: its childOf throws, answers no job, or answers a
     * job whose state is null. The call aborts on that hop, and the abort holds the exception that selected RETRY.
     */
    @Test
    void aSubMachineThatTheRecoveryHopCannotStartKeepsTheOriginalFailureAsSuppressed() {
        IllegalArgumentException down = new IllegalArgumentException("the job store is down");
        Map<String, Function<Batch, Optional<Job>>> lookups = new LinkedHashMap<>();
        lookups.put("the childOf of Batch/BatchState.WORKING threw " + down, (Batch batch) -> {
            throw down;
        });
        lookups.put("Batch/BatchState entered WORKING with no Job/JobState", (Batch batch) -> Optional.empty());
        lookups.put("reading the sub-machine of Batch/BatchState.WORKING threw ",
                (Batch batch) -> Optional.of(new Job("j1", null)));
        for (Map.Entry<String, Function<Batch, Optional<Job>>> lookup : lookups.entrySet()) {
            jobOf = lookup.getValue();
            Batch batch = new Batch("b1", BatchState.OPEN);
            try (MachineRegistry registry = registry(batchGraph, 10)) {
                TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                        () -> registry.of(BATCHES).execute(batch, BatchChange.START), lookup.getKey());
                assertTrue(error.getMessage().contains("OPEN -RETRY-> WORKING: " + lookup.getKey()),
                        error.getMessage());
                assertEquals(1, error.getSuppressed().length, lookup.getKey());
                assertSame(original, error.getSuppressed()[0], lookup.getKey());
                assertEquals(BatchState.OPEN, batch.state());
            }
        }
    }

    /**
     * A kicked job recovers into FAILED at depth 1, and the exit END that FAILED selects in its batch would run at
     * depth 2, beyond the limit of 1: the abort of the job's step holds the exception that selected FAIL.
     */
    @Test
    void anExitBeyondTheDepthLimitAfterARecoveryKeepsTheOriginalFailureAsSuppressed() {
        Job held = new Job("j1", JobState.WAITING);
        Batch batch = holding(held);
        Job kicker = new Job("k1", JobState.WAITING);
        try (MachineRegistry registry = registry(batchGraph, 1)) {
            CascadeDepthExceededException error = assertThrows(CascadeDepthExceededException.class,
                    () -> registry.of(JOBS).execute(kicker, JobChange.KICK, held));
            assertTrue(error.getMessage().contains("Job/JobState WAITING -FAIL-> FAILED"), error.getMessage());
            assertEquals(1, error.getSuppressed().length);
            assertSame(original, error.getSuppressed()[0]);
            assertEquals(List.of(JobState.WAITING, JobState.WAITING, BatchState.WORKING),
                    List.of(kicker.state(), held.state(), batch.state()));
        }
    }

    /**
     * A job recovers into FAILED, to which a hand-written batch graph maps no exit: the abort of the job's step holds
     * the exception that selected FAIL.
     */
    @Test
    void aTerminalWithNoExitAfterARecoveryKeepsTheOriginalFailureAsSuppressed() {
        Job held = new Job("j1", JobState.WAITING);
        Batch batch = holding(held);
        try (MachineRegistry registry = registry(new WithoutExits(batchGraph), 10)) {
            TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                    () -> registry.of(JOBS).execute(held, JobChange.RUN));
            assertTrue(error.getMessage().contains("WAITING -FAIL-> FAILED: the terminal FAILED of Job/JobState has no"
                    + " exit in WORKING"), error.getMessage());
            assertEquals(1, error.getSuppressed().length);
            assertSame(original, error.getSuppressed()[0]);
            assertEquals(List.of(JobState.WAITING, BatchState.WORKING), List.of(held.state(), batch.state()));
        }
    }

    /**
     * The recovery hop RESEAL enters SEALING, whose seal starts in DONE, a terminal state, to which a hand-written
     * batch graph maps no exit: the abort of the batch's step holds the exception that selected RESEAL.
     */
    @Test
    void aStartedTerminalWithNoExitAfterARecoveryKeepsTheOriginalFailureAsSuppressed() {
        Batch batch = new Batch("b1", BatchState.OPEN);
        try (MachineRegistry registry = registry(new WithoutExits(batchGraph), 10)) {
            TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                    () -> registry.of(BATCHES).execute(batch, BatchChange.SEAL));
            assertTrue(error.getMessage().contains("OPEN -RESEAL-> SEALING: the terminal DONE of Batch/SealState has"
                    + " no exit in SEALING"), error.getMessage());
            assertEquals(1, error.getSuppressed().length);
            assertSame(original, error.getSuppressed()[0]);
            assertEquals(BatchState.OPEN, batch.state());
        }
    }

    /**
     * The batch graph by hand, whose binding of WORKING maps no exit to FAILED and whose binding of SEALING maps none
     * to DONE, which the DSL refuses.
     */
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    private static final class WithoutExits implements GraphDefinition<Batch, BatchState, BatchChange> {

        private final GraphDefinition<Batch, BatchState, BatchChange> graph;

        private static <P, Q extends Enum<Q>, U extends Enum<U>>
                SubmachineSpec<Batch, BatchState, BatchChange, P, Q, U> rewritten(
                        SubmachineSpec<Batch, BatchState, BatchChange, P, Q, U> spec) {
            Map<Q, BatchChange> exits = new LinkedHashMap<>(spec.exits());
            exits.keySet().removeIf((Q terminal) -> terminal == JobState.FAILED || terminal == SealState.DONE);
            return new SubmachineSpec<>(spec.state(), spec.child(), spec.childOf(), spec.parentOf(), exits);
        }

        @Override
        public MachineKey<Batch, BatchState, BatchChange> key() { return graph.key(); }

        @Override
        public BatchState initial() { return graph.initial(); }

        @Override
        public Set<BatchState> terminals() { return graph.terminals(); }

        @Override
        public DirectedGraph<BatchState, BatchChange> graph() { return graph.graph(); }

        @Override
        public List<TransitionSpec<Batch, BatchState, BatchChange>> transitions() { return graph.transitions(); }

        @Override
        public List<TransitionSpec<Batch, BatchState, BatchChange>> outgoingFrom(BatchState state) {
            return graph.outgoingFrom(state);
        }

        @Override
        public Optional<TransitionSpec<Batch, BatchState, BatchChange>> findTransition(BatchState from,
                                                                                     BatchChange transition) {
            return graph.findTransition(from, transition);
        }

        @Override
        public StatePolicy<BatchState, BatchChange> policy(BatchState state) { return graph.policy(state); }

        @Override
        public Optional<WatchSpec<Batch, BatchState, BatchChange, ?, ?, ?>> watch(BatchState state) {
            return graph.watch(state);
        }

        @Override
        public Optional<SubmachineSpec<Batch, BatchState, BatchChange, ?, ?, ?>> submachine(BatchState state) {
            Optional<SubmachineSpec<Batch, BatchState, BatchChange, ?, ?, ?>> spec = graph.submachine(state);
            return spec.isPresent() ? Optional.of(rewritten(spec.get())) : spec;
        }

        @Override
        public boolean hasAsync() { return graph.hasAsync(); }
    }
}
