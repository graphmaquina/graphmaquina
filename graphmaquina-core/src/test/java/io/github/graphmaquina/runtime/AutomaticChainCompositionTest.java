package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.api.WatchCondition.allIn;
import static io.github.graphmaquina.fixtures.StepTexts.described;
import static io.github.graphmaquina.runtime.AutomaticChainCompositionTest.JobStatus.CHECKED;
import static io.github.graphmaquina.runtime.AutomaticChainCompositionTest.JobStatus.DOING;
import static io.github.graphmaquina.runtime.AutomaticChainCompositionTest.JobStatus.DONE;
import static io.github.graphmaquina.runtime.AutomaticChainCompositionTest.JobStatus.HELD;
import static io.github.graphmaquina.runtime.AutomaticChainCompositionTest.JobStatus.STAGED;
import static io.github.graphmaquina.runtime.AutomaticChainCompositionTest.JobStatus.TODO;
import static io.github.graphmaquina.runtime.AutomaticChainCompositionTest.JobTransition.CHECK;
import static io.github.graphmaquina.runtime.AutomaticChainCompositionTest.JobTransition.FINISH;
import static io.github.graphmaquina.runtime.AutomaticChainCompositionTest.JobTransition.HOLD;
import static io.github.graphmaquina.runtime.AutomaticChainCompositionTest.JobTransition.LIFT;
import static io.github.graphmaquina.runtime.AutomaticChainCompositionTest.JobTransition.RELEASE;
import static io.github.graphmaquina.runtime.AutomaticChainCompositionTest.JobTransition.SHIP;
import static io.github.graphmaquina.runtime.AutomaticChainCompositionTest.JobTransition.SKIP;
import static io.github.graphmaquina.runtime.AutomaticChainCompositionTest.JobTransition.START;
import static io.github.graphmaquina.runtime.AutomaticChainCompositionTest.JobTransition.TRY;
import static io.github.graphmaquina.runtime.AutomaticChainCompositionTest.JobTransition.UNSTAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.AutomaticStep;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.OutcomeCode;
import io.github.graphmaquina.api.Reaction;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.TransitionRecord;
import io.github.graphmaquina.dsl.StateGraph;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;

/**
 * How automatic chains compose with watches, cascades, sub-machines and hooks, and which commit chains an entity: the
 * deepest one that moved it. A call nested in a post-commit, a hook's call, a watch reaction or an attempt, that
 * commits a hop of an entity decides that entity's chain, so the commit around it does not chain it again, and the
 * loop of a chain ends when a call nested in its attempt's post-commit moved its entity. Jobs on a board, whose watch
 * nudges it, and a lot whose seal is a sub-machine show each case.
 */
class AutomaticChainCompositionTest {

    /** States of a job. */
    enum JobStatus { TODO, DOING, CHECKED, STAGED, HELD, DONE }

    /** Identifiers of the job machine. */
    enum JobTransition { START, CHECK, FINISH, SKIP, UNSTAGE, SHIP, LIFT, TRY, HOLD, RELEASE }

    /** States of a board. */
    enum BoardStatus { OPEN, NUDGED }

    /** The only identifier of the board machine. */
    enum BoardTransition { NUDGE }

    /** States of a lot, which waits in SEALING for its seal. */
    enum LotStage { OPEN, SEALING, CLOSED, ARCHIVED }

    /** Identifiers of the lot machine. */
    enum LotMove { SEAL, CLOSE, ARCHIVE }

    /** States of a lot's seal, the sub-machine of SEALING. */
    enum SealStatus { UNSEALED, SEALED }

    /** The only identifier of the seal machine. */
    enum SealMove { PRESS }

    static final MachineKey<Job, JobStatus, JobTransition> JOBS =
            MachineKey.of(Job.class, JobStatus.class, JobTransition.class);

    static final MachineKey<Board, BoardStatus, BoardTransition> BOARDS =
            MachineKey.of(Board.class, BoardStatus.class, BoardTransition.class);

    static final MachineKey<Lot, LotStage, LotMove> LOTS = MachineKey.of(Lot.class, LotStage.class, LotMove.class);

    static final MachineKey<Lot, SealStatus, SealMove> SEALS =
            MachineKey.of(Lot.class, SealStatus.class, SealMove.class);

    /** A job: an id, a status and the board it is on, if any. */
    @Getter(AccessLevel.PACKAGE)
    static final class Job {

        private final String id;
        private JobStatus status;
        private Optional<Board> board = Optional.empty();

        Job(String id, JobStatus status) {
            this.id = id;
            this.status = status;
        }

        void setStatus(JobStatus status) { this.status = status; }
    }

    /** A board and the jobs on it, which its watch looks at. */
    @Getter(AccessLevel.PACKAGE)
    static final class Board {

        private final String id;
        private BoardStatus status = BoardStatus.OPEN;
        private final List<Job> jobs = new ArrayList<>();

        Board(String id, Job... jobs) {
            this.id = id;
            for (Job job : jobs) {
                this.jobs.add(job);
                job.board = Optional.of(this);
            }
        }

        void setStatus(BoardStatus status) { this.status = status; }
    }

    /** A lot and its seal, two states of one entity. */
    @Getter(AccessLevel.PACKAGE)
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    static final class Lot {

        private final String id;
        private LotStage stage = LotStage.OPEN;
        private SealStatus seal = SealStatus.UNSEALED;

        void setStage(LotStage stage) { this.stage = stage; }

        void setSeal(SealStatus seal) { this.seal = seal; }
    }

    /**
     * An identity of a job, which each call of its identity function builds anew: two of them compare by the job's id
     * until the job is DONE, and comparing two of them throws from then on.
     *
     * @param job the job it identifies
     */
    record Fussy(Job job) {

        @Override
        public boolean equals(Object other) {
            if (other == this) {
                return true;
            }
            if (job.status() == DONE) {
                throw new IllegalStateException("two identities of a done job compared");
            }
            return other instanceof Fussy that && that.job.id().equals(job.id());
        }

        @Override
        public int hashCode() {
            return job.id().hashCode();
        }
    }

    /** The after-commit hook of START. */
    private Consumer<Job> onStart = (Job job) -> { };
    /** The action of HOLD, the failure route of TRY. */
    private Consumer<TransitionContext<Job, JobStatus, JobTransition>> onHold =
            (TransitionContext<Job, JobStatus, JobTransition> ctx) -> { };
    /** The action of NUDGE. */
    private Consumer<TransitionContext<Board, BoardStatus, BoardTransition>> nudge =
            (TransitionContext<Board, BoardStatus, BoardTransition> ctx) -> { };

    /**
     * The job graph: TODO -START-> DOING, with the hook {@link #onStart}; DOING -CHECK-> CHECKED -FINISH-> DONE, both
     * automatic; DOING -SKIP-> STAGED and back by UNSTAGE; STAGED -SHIP-> DONE, automatic; CHECKED -LIFT-> STAGED;
     * DOING -TRY-> STAGED, whose action fails, and whose route HOLD to HELD runs {@link #onHold} and does not
     * continue; HELD -RELEASE-> DONE, automatic.
     */
    private GraphDefinition<Job, JobStatus, JobTransition> jobs() {
        return StateGraph.define(JOBS).initial(TODO).terminal(DONE).allowsCycle(DOING)
                .statePolicy(TODO, "a job starts").statePolicy(DOING, "a job is checked")
                .statePolicy(CHECKED, "a checked job finishes").statePolicy(STAGED, "a staged job ships")
                .statePolicy(HELD, "a held job is released")
                .transition(START, TODO, DOING).afterCommit("started", (Job job) -> onStart.accept(job))
                    .endTransition()
                .transition(CHECK, DOING, CHECKED).automatic().endTransition()
                .transition(FINISH, CHECKED, DONE).automatic().endTransition()
                .transition(SKIP, DOING, STAGED).endTransition()
                .transition(UNSTAGE, STAGED, DOING).endTransition()
                .transition(SHIP, STAGED, DONE).automatic().endTransition()
                .transition(LIFT, CHECKED, STAGED).endTransition()
                .transition(TRY, DOING, STAGED)
                    .action("try", (TransitionContext<Job, JobStatus, JobTransition> ctx) ->
                            ActionOutcome.failed("the job is not ready"))
                    .onFailure(HOLD).endTransition()
                .transition(HOLD, DOING, HELD)
                    .action("hold", (TransitionContext<Job, JobStatus, JobTransition> ctx) -> {
                        onHold.accept(ctx);
                        return ActionOutcome.ok();
                    }).endTransition()
                .transition(RELEASE, HELD, DONE).automatic().endTransition()
                .build();
    }

    /** The board graph: OPEN -NUDGE-> NUDGED, running {@link #nudge}, once every job on it is in {@code watched}. */
    private GraphDefinition<Board, BoardStatus, BoardTransition> boards(JobStatus watched) {
        return StateGraph.define(BOARDS).initial(BoardStatus.OPEN).terminal(BoardStatus.NUDGED)
                .statePolicy(BoardStatus.OPEN, "a board is nudged")
                .transition(BoardTransition.NUDGE, BoardStatus.OPEN, BoardStatus.NUDGED)
                    .action("nudge", (TransitionContext<Board, BoardStatus, BoardTransition> ctx) -> {
                        nudge.accept(ctx);
                        return ActionOutcome.ok();
                    }).endTransition()
                .watch(BoardStatus.OPEN, JOBS, Board::jobs, Job::board)
                    .when(allIn(watched)).execute(BoardTransition.NUDGE)
                    .endWatch()
                .build();
    }

    /**
     * Jobs, whose identity is {@code identity}, and boards, whose watch waits for {@code watched}, under
     * {@code maxAutomaticChain}.
     */
    private MachineRegistry registry(JobStatus watched, int maxAutomaticChain, Function<Job, Object> identity) {
        return MachineRegistry.builder().maxAutomaticChain(maxAutomaticChain)
                .add(StateMachines.from(jobs()).state(Job::status, Job::setStatus).identity(identity))
                .add(StateMachines.from(boards(watched)).state(Board::status, Board::setStatus).identity(Board::id))
                .build();
    }

    private MachineRegistry registry(JobStatus watched) {
        return registry(watched, 10, Job::id);
    }

    /**
     * The lot graph: OPEN -SEAL-> SEALING, bound to the seal, whose SEALED exits by CLOSE to CLOSED; CLOSED -ARCHIVE->
     * ARCHIVED, automatic. The seal starts UNSEALED, and PRESS to SEALED is automatic.
     */
    private static MachineRegistry lots() {
        GraphDefinition<Lot, SealStatus, SealMove> seals = StateGraph.define(SEALS)
                .initial(SealStatus.UNSEALED).terminal(SealStatus.SEALED)
                .statePolicy(SealStatus.UNSEALED, "a seal is pressed")
                .transition(SealMove.PRESS, SealStatus.UNSEALED, SealStatus.SEALED).automatic().endTransition()
                .build();
        GraphDefinition<Lot, LotStage, LotMove> lots = StateGraph.define(LOTS)
                .initial(LotStage.OPEN).terminal(LotStage.ARCHIVED)
                .statePolicy(LotStage.OPEN, "a lot is sealed").statePolicy(LotStage.SEALING, "a lot waits for its seal")
                .statePolicy(LotStage.CLOSED, "a closed lot is archived")
                .transition(LotMove.SEAL, LotStage.OPEN, LotStage.SEALING).endTransition()
                .transition(LotMove.CLOSE, LotStage.SEALING, LotStage.CLOSED).endTransition()
                .transition(LotMove.ARCHIVE, LotStage.CLOSED, LotStage.ARCHIVED).automatic().endTransition()
                .submachine(LotStage.SEALING, seals).exit(SealStatus.SEALED, LotMove.CLOSE).endSubmachine()
                .build();
        return MachineRegistry.builder()
                .add(StateMachines.from(lots).state(Lot::stage, Lot::setStage).identity(Lot::id))
                .add(StateMachines.from(seals).state(Lot::seal, Lot::setSeal).identity(Lot::id))
                .build();
    }

    /** The outcome of the step at {@code index}, which ran to completion. */
    private static Outcome<?, ?, ?> attempt(List<AutomaticStep> steps, int index) {
        return assertInstanceOf(AutomaticStep.Done.class, steps.get(index)).outcome();
    }

    /** The outcome of the reaction at {@code index}, which ran to completion. */
    private static Outcome<?, ?, ?> reaction(Outcome<?, ?, ?> outcome, int index) {
        return assertInstanceOf(Reaction.Done.class, outcome.reactions().get(index)).outcome();
    }

    /**
     * The chain runs after the watch dispatch of the commit that triggers it: the board, waiting for every job in
     * DOING, sees the job there although the job then moves on by itself.
     */
    @Test
    void aWatcherOfAnIntermediateStateSeesIt() {
        Job job = new Job("j1", TODO);
        Board board = new Board("b1", job);
        try (MachineRegistry registry = registry(DOING)) {
            Outcome<Job, JobStatus, JobTransition> outcome = registry.of(JOBS).execute(job, START);
            assertEquals(List.of(OutcomeCode.APPLIED, Optional.of(BoardTransition.NUDGE)),
                    List.of(reaction(outcome, 0).code(), reaction(outcome, 0).requestedTransition()));
            assertEquals(List.of("Done APPLIED CHECK CHECKED", "Done APPLIED FINISH DONE"),
                    described(outcome.automaticSteps()));
        }
        assertEquals(List.of(BoardStatus.NUDGED, DONE), List.of(board.status(), job.status()));
    }

    /** A peer that a cascade moved runs its chain after the commit, listed among the steps of the cascading call. */
    @Test
    void aCascadedPeerChainsAfterTheCommit() {
        Job job = new Job("j1", TODO);
        nudge = (TransitionContext<Board, BoardStatus, BoardTransition> ctx) -> ctx.cascade(JOBS, job, START);
        try (MachineRegistry registry = registry(DOING)) {
            Outcome<Board, BoardStatus, BoardTransition> outcome =
                    registry.of(BOARDS).execute(new Board("b1"), BoardTransition.NUDGE);
            assertEquals(List.of("Done APPLIED CHECK CHECKED", "Done APPLIED FINISH DONE"),
                    described(outcome.automaticSteps()));
            AutomaticStep step = outcome.automaticSteps().get(0);
            assertEquals(List.of(JOBS, job), List.of(step.key(), step.entity()));
        }
        assertEquals(DONE, job.status());
    }

    /**
     * A job that a cascade moves away and back within one commit chains from where it stands, although its state did
     * not change, which a watch would not react to.
     */
    @Test
    void aRoundTripTriggersTheChain() {
        Job job = new Job("j1", DOING);
        nudge = (TransitionContext<Board, BoardStatus, BoardTransition> ctx) -> {
            ctx.cascade(JOBS, job, SKIP);
            ctx.cascade(JOBS, job, UNSTAGE);
        };
        try (MachineRegistry registry = registry(DOING)) {
            Outcome<Board, BoardStatus, BoardTransition> outcome =
                    registry.of(BOARDS).execute(new Board("b1"), BoardTransition.NUDGE);
            assertEquals(List.of(BoardTransition.NUDGE, SKIP, UNSTAGE),
                    outcome.records().stream().map(TransitionRecord::transition).toList());
            assertEquals(List.of("Done APPLIED CHECK CHECKED", "Done APPLIED FINISH DONE"),
                    described(outcome.automaticSteps()));
        }
        assertEquals(DONE, job.status());
    }

    /**
     * The rule after a failure is per entity: the recovery action of a route that does not continue cascades a peer,
     * which runs its chain, while the job that failed stays in its recovery state, whose automatic edge does not run.
     */
    @Test
    void theAfterFailureRuleIsPerEntity() {
        Job failing = new Job("j1", DOING);
        Job peer = new Job("j2", TODO);
        onHold = (TransitionContext<Job, JobStatus, JobTransition> ctx) -> ctx.cascade(JOBS, peer, START);
        try (MachineRegistry registry = registry(DOING)) {
            Outcome<Job, JobStatus, JobTransition> outcome = registry.of(JOBS).execute(failing, TRY);
            assertEquals(OutcomeCode.FAILURE_ROUTED, outcome.code());
            assertEquals(List.of("Done APPLIED CHECK CHECKED", "Done APPLIED FINISH DONE"),
                    described(outcome.automaticSteps()));
            assertSame(peer, outcome.automaticSteps().get(0).entity());
        }
        assertEquals(List.of(HELD, DONE), List.of(failing.status(), peer.status()));
    }

    /**
     * The commit moves the job to DOING; the board's reaction moves it on to STAGED, and the reaction's commit, the
     * deepest that moved it, chains it. The commit of the call does not chain it again.
     */
    @Test
    void aReactionThatMovesAnEntityChainsItOnce() {
        Job job = new Job("j1", TODO);
        new Board("b1", job);
        nudge = (TransitionContext<Board, BoardStatus, BoardTransition> ctx) -> ctx.cascade(JOBS, job, SKIP);
        try (MachineRegistry registry = registry(DOING)) {
            Outcome<Job, JobStatus, JobTransition> outcome = registry.of(JOBS).execute(job, START);
            assertEquals(List.of(), outcome.automaticSteps());
            assertEquals(List.of("Done APPLIED SHIP DONE"), described(reaction(outcome, 0).automaticSteps()));
        }
        assertEquals(DONE, job.status());
    }

    /**
     * The board's reaction moves the job by a failure route that does not continue: that commit, the deepest that
     * moved the job, stops its chain, and the commit of the call does not chain it either, so the job stays held.
     */
    @Test
    void aReactionMovingAnEntityByAStoppingRouteKeepsTheOriginalCommitFromChainingIt() {
        Job job = new Job("j1", TODO);
        new Board("b1", job);
        nudge = (TransitionContext<Board, BoardStatus, BoardTransition> ctx) -> ctx.cascade(JOBS, job, TRY);
        try (MachineRegistry registry = registry(DOING)) {
            Outcome<Job, JobStatus, JobTransition> outcome = registry.of(JOBS).execute(job, START);
            assertEquals(List.of(), outcome.automaticSteps());
            assertEquals(List.of(), reaction(outcome, 0).automaticSteps());
        }
        assertEquals(HELD, job.status());
    }

    /**
     * The first attempt of the job's chain moves it to CHECKED, where the board's reaction to that attempt's commit
     * lifts it to STAGED and chains it from there: the job's loop ends after that attempt.
     */
    @Test
    void aReactionOfAnAttemptMovingTheChainEntityEndsThatLoop() {
        Job job = new Job("j1", TODO);
        new Board("b1", job);
        nudge = (TransitionContext<Board, BoardStatus, BoardTransition> ctx) -> ctx.cascade(JOBS, job, LIFT);
        try (MachineRegistry registry = registry(CHECKED)) {
            Outcome<Job, JobStatus, JobTransition> outcome = registry.of(JOBS).execute(job, START);
            assertEquals(OutcomeCode.NO_RULE_MATCHED, reaction(outcome, 0).code());
            assertEquals(List.of("Done APPLIED CHECK CHECKED"), described(outcome.automaticSteps()));
            Outcome<?, ?, ?> lifted = reaction(attempt(outcome.automaticSteps(), 0), 0);
            assertEquals(Optional.of(BoardTransition.NUDGE), lifted.requestedTransition());
            assertEquals(List.of("Done APPLIED SHIP DONE"), described(lifted.automaticSteps()));
        }
        assertEquals(DONE, job.status());
    }

    /** A sub-machine started by its parent counts as moved: it chains from its initial state. */
    @Test
    void aStartedChildChainsFromItsInitial() {
        Lot lot = new Lot("l1");
        try (MachineRegistry registry = lots()) {
            Outcome<Lot, LotStage, LotMove> outcome = registry.of(LOTS).execute(lot, LotMove.SEAL);
            assertEquals(1, outcome.starts().size());
            assertEquals(List.of("Done APPLIED PRESS SEALED"), described(outcome.automaticSteps()));
            assertEquals(SEALS, outcome.automaticSteps().get(0).key());
        }
        assertEquals(SealStatus.SEALED, lot.seal());
    }

    /**
     * The seal's attempt reaches its terminal state, so the lot's exit runs inside that attempt's call: the lot moves
     * there, and its chain runs nested in the attempt's post-commit, listed in the attempt's outcome.
     */
    @Test
    void aChildExitMovesTheParentWhoseChainRunsNestedInTheChildsAttempt() {
        Lot lot = new Lot("l1");
        try (MachineRegistry registry = lots()) {
            Outcome<Lot, LotStage, LotMove> outcome = registry.of(LOTS).execute(lot, LotMove.SEAL);
            Outcome<?, ?, ?> pressed = attempt(outcome.automaticSteps(), 0);
            assertEquals(List.of(SealMove.PRESS, LotMove.CLOSE),
                    pressed.records().stream().map(TransitionRecord::transition).toList());
            assertEquals(List.of("Done APPLIED ARCHIVE ARCHIVED"), described(pressed.automaticSteps()));
            assertEquals(LOTS, pressed.automaticSteps().get(0).key());
        }
        assertEquals(LotStage.ARCHIVED, lot.stage());
    }

    /**
     * A hook of the commit starts a call that moves the job on: that call's commit, the deepest that moved the job,
     * chains it, and the commit whose hook ran does not chain it again.
     */
    @Test
    void aHookStartedCallThatMovesAnEntityOwnsItsChain() {
        Job job = new Job("j1", TODO);
        List<Outcome<Job, JobStatus, JobTransition>> hooked = new ArrayList<>();
        try (MachineRegistry registry = registry(DOING)) {
            StateMachine<Job, JobStatus, JobTransition> jobs = registry.of(JOBS);
            onStart = (Job started) -> hooked.add(jobs.execute(started, SKIP));
            Outcome<Job, JobStatus, JobTransition> outcome = jobs.execute(job, START);
            assertEquals(List.of(), outcome.automaticSteps());
        }
        assertEquals(List.of("Done APPLIED SHIP DONE"), described(hooked.get(0).automaticSteps()));
        assertEquals(DONE, job.status());
    }

    /**
     * A hook writes the job's status behind the library's back. The chain starts from the state the commit logged,
     * which has an automatic edge, and its attempt reads the live state: a state without automatic edges ends the
     * chain with an Ignored of NO_AUTOMATIC_TRANSITION, and one with them goes on from there.
     */
    @Test
    void aStateChangedBehindTheLibrarysBackIsReadLive() {
        Job done = new Job("j1", TODO);
        Job checked = new Job("j2", TODO);
        onStart = (Job started) -> started.setStatus(started == done ? DONE : CHECKED);
        try (MachineRegistry registry = registry(DOING)) {
            assertEquals(List.of("Done NO_AUTOMATIC_TRANSITION DONE"),
                    described(registry.of(JOBS).execute(done, START).automaticSteps()));
            assertEquals(List.of("Done APPLIED FINISH DONE"),
                    described(registry.of(JOBS).execute(checked, START).automaticSteps()));
        }
        assertEquals(List.of(DONE, DONE), List.of(done.status(), checked.status()));
    }

    /**
     * Twenty jobs that one commit moved each start their chain at level 1, as siblings do not add up: under a limit
     * of 1, each first attempt runs, and each second one, at level 2, aborts.
     */
    @Test
    void aFanOutOfTwentyStaysAtLevelOne() {
        List<Job> jobs = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            jobs.add(new Job("j" + index, TODO));
        }
        nudge = (TransitionContext<Board, BoardStatus, BoardTransition> ctx) -> {
            for (Job job : jobs) {
                ctx.cascade(JOBS, job, START);
            }
        };
        List<String> steps;
        try (MachineRegistry registry = registry(DOING, 1, Job::id)) {
            steps = described(registry.of(BOARDS).execute(new Board("b1"), BoardTransition.NUDGE).automaticSteps());
        }
        List<String> expected = new ArrayList<>();
        for (Job job : jobs) {
            expected.add("Done APPLIED CHECK CHECKED");
            expected.add("Aborted AutomaticChainExceededException");
            assertEquals(CHECKED, job.status());
        }
        assertEquals(expected, steps);
    }

    /**
     * Before the job's chain starts, the commit of the call compares the job with the entities that its reaction moved,
     * by identity, outside any attempt, once the reaction's chain left the job DONE: an identity whose equals throws
     * there ends that chain as an aborted step with what it threw, the call returns, and the reaction, the deepest
     * commit that moved the job, chained it.
     */
    @Test
    void anIdentityThatThrowsWhenComparedAbortsTheChain() {
        Job job = new Job("j1", TODO);
        new Board("b1", job);
        nudge = (TransitionContext<Board, BoardStatus, BoardTransition> ctx) -> ctx.cascade(JOBS, job, SKIP);
        try (MachineRegistry registry = registry(DOING, 10, Fussy::new)) {
            Outcome<Job, JobStatus, JobTransition> outcome = registry.of(JOBS).execute(job, START);
            assertEquals(OutcomeCode.APPLIED, outcome.code());
            assertEquals(List.of("Aborted IllegalStateException"), described(outcome.automaticSteps()));
            assertEquals(List.of("Done APPLIED SHIP DONE"), described(reaction(outcome, 0).automaticSteps()));
        }
        assertEquals(DONE, job.status());
    }

    /**
     * The board's commit cascades the job, whose hook's call moves it on and chains it to DONE. When the commit's own
     * chains start, the board first, the scope indexes the two entries of the job that call reported, and the job's
     * identity throws comparing them: that ends no chain but the job's own, so the board, which has no automatic edge,
     * makes no step, and the job's chain ends as an aborted step when its own identity throws.
     */
    @Test
    void anIdentityThatThrowsEndsOnlyItsOwnChain() {
        Job job = new Job("j1", TODO);
        nudge = (TransitionContext<Board, BoardStatus, BoardTransition> ctx) -> ctx.cascade(JOBS, job, START);
        List<Outcome<Job, JobStatus, JobTransition>> hooked = new ArrayList<>();
        try (MachineRegistry registry = registry(DOING, 10, Fussy::new)) {
            StateMachine<Job, JobStatus, JobTransition> jobs = registry.of(JOBS);
            onStart = (Job started) -> hooked.add(jobs.execute(started, SKIP));
            Outcome<Board, BoardStatus, BoardTransition> outcome =
                    registry.of(BOARDS).execute(new Board("b1"), BoardTransition.NUDGE);
            assertEquals(List.of("Aborted IllegalStateException"), described(outcome.automaticSteps()));
            assertSame(job, outcome.automaticSteps().get(0).entity());
        }
        assertEquals(List.of("Done APPLIED SHIP DONE"), described(hooked.get(0).automaticSteps()));
        assertEquals(DONE, job.status());
    }
}
