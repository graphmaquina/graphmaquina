package io.github.graphmaquina.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.CallFailure;
import io.github.graphmaquina.api.CascadePolicy;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.MachineCall;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.MachineObserver;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.OutcomeCode;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.error.CascadeCycleException;
import io.github.graphmaquina.error.CascadeException;
import io.github.graphmaquina.error.CommitFailedException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.fixtures.Sneaky;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import lombok.AccessLevel;
import lombok.Getter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The engine names the states and identifiers of the application's enums by {@code name()} in the aborts, the commit
 * failures and the rejection reasons it builds, never through a {@code toString()} the application may override: one
 * that throws cannot keep a failed cascade from poisoning the call, nor a failure of the root step from reaching the
 * observers as the call's failure, and one that answers a display text changes no message.
 */
class UnprintableConstantTest {

    /** States of a paper, printed as a display text, and not printable at all while {@link #unprintable} holds one. */
    enum Stage {
        DRAFT, REVIEW, PUBLISHED, ARCHIVED;

        /** What the {@code toString()} of every constant throws while it is present. */
        static Optional<Throwable> unprintable = Optional.empty();

        @Override
        public String toString() {
            if (unprintable.isPresent()) {
                throw Sneaky.raise(unprintable.get());
            }
            return "stage " + name().toLowerCase(Locale.ROOT);
        }
    }

    /** Identifiers of a paper, printed as a display text. */
    enum Move {
        SUBMIT, PUBLISH, ARCHIVE;

        @Override
        public String toString() {
            return "move " + name().toLowerCase(Locale.ROOT);
        }
    }

    /** States of a paper's seal, a sub-machine of the same entity, printed as a display text. */
    enum Seal {
        OPEN, SEALED;

        @Override
        public String toString() {
            return "seal " + name().toLowerCase(Locale.ROOT);
        }
    }

    /** The identifier of the seal machine, printed as a display text. */
    enum SealMove {
        CLOSE;

        @Override
        public String toString() {
            return "seal move " + name().toLowerCase(Locale.ROOT);
        }
    }

    static final MachineKey<Paper, Stage, Move> PAPERS = MachineKey.of(Paper.class, Stage.class, Move.class);
    static final MachineKey<Paper, Seal, SealMove> SEALS = MachineKey.of(Paper.class, Seal.class, SealMove.class);

    /** A paper, with its stage and its seal; it prints by id. */
    @Getter(AccessLevel.PACKAGE)
    static final class Paper {
        private final String id;
        private Stage stage;
        private Seal seal = Seal.OPEN;

        Paper(String id, Stage stage) {
            this.id = id;
            this.stage = stage;
        }

        void setStage(Stage stage) { this.stage = stage; }

        void setSeal(Seal seal) { this.seal = seal; }

        @Override
        public String toString() { return "Paper#" + id; }
    }

    /** Papers: SUBMIT runs {@code submit}, and PUBLISH is refused for {@code refused}. */
    private static GraphDefinition<Paper, Stage, Move> papers(Consumer<TransitionContext<Paper, Stage, Move>> submit,
                                                             Optional<Paper> refused) {
        return StateGraph.define(PAPERS).initial(Stage.DRAFT).terminal(Stage.ARCHIVED)
                .statePolicy(Stage.DRAFT, "a draft is submitted").statePolicy(Stage.REVIEW, "a review is published")
                .statePolicy(Stage.PUBLISHED, "a publication is archived")
                .transition(Move.SUBMIT, Stage.DRAFT, Stage.REVIEW)
                    .action("Submit", (TransitionContext<Paper, Stage, Move> ctx) -> {
                        submit.accept(ctx);
                        return ActionOutcome.ok();
                    })
                    .endTransition()
                .transition(Move.PUBLISH, Stage.REVIEW, Stage.PUBLISHED)
                    .guard("NotRefused", (GuardContext<Paper, Stage, Move> ctx) ->
                            refused.isPresent() && ctx.entity() == refused.get()
                                    ? GuardResult.reject("refused") : GuardResult.pass())
                    .endTransition()
                .transition(Move.ARCHIVE, Stage.PUBLISHED, Stage.ARCHIVED).endTransition()
                .build();
    }

    /** A registry of papers whose state getter throws for {@code unreadable}. */
    private static MachineRegistry registry(GraphDefinition<Paper, Stage, Move> graph, Optional<Paper> unreadable,
                                            List<String> events) {
        return MachineRegistry.builder().observer(recording(events)).add(StateMachines.from(graph).state(
                (Paper paper) -> {
                    if (unreadable.isPresent() && paper == unreadable.get()) {
                        throw new IllegalArgumentException("the state of " + paper.id() + " cannot be read");
                    }
                    return paper.stage();
                }, Paper::setStage).identity(Paper::id)).build();
    }

    private static MachineObserver recording(List<String> events) {
        return new MachineObserver() {
            @Override
            public void onStarted(MachineCall call) {
                events.add("started");
            }

            @Override
            public void onCompleted(MachineCall call, Outcome<?, ?, ?> outcome, Duration elapsed) {
                events.add("completed " + outcome.code());
            }

            @Override
            public void onFailed(MachineCall call, CallFailure failure, Duration elapsed) {
                events.add("failed " + failure.error().getClass().getSimpleName());
            }
        };
    }

    private static Throwable failure(String kind) {
        return kind.equals("error") ? new AssertionError("a stage cannot be printed")
                : new IllegalStateException("a stage cannot be printed");
    }

    @ParameterizedTest
    @CsvSource({"cascade, runtime", "cascade, error", "collect, runtime", "collect, error", "aggregate, runtime",
            "aggregate, error"})
    void aCascadeThatFailsWhileNoStageCanBePrintedStillPoisonsTheCall(String cascade, String kind) {
        Paper root = new Paper("root", Stage.DRAFT);
        Paper good = new Paper("good", Stage.REVIEW);
        Paper failing = new Paper("failing", Stage.REVIEW);
        List<Throwable> caught = new ArrayList<>();
        Consumer<TransitionContext<Paper, Stage, Move>> submit = (TransitionContext<Paper, Stage, Move> ctx) -> {
            Stage.unprintable = Optional.of(failure(kind));
            try {
                switch (cascade) {
                    case "cascade" -> ctx.cascade(PAPERS, failing, Move.PUBLISH);
                    default -> ctx.cascadeAll(PAPERS, List.of(good, failing), Move.PUBLISH, CascadePolicy.COLLECT);
                }
            } catch (RuntimeException | Error thrown) {
                caught.add(thrown);
            } finally {
                Stage.unprintable = Optional.empty();
            }
        };
        boolean aggregate = cascade.equals("aggregate");
        Optional<Paper> refused = aggregate ? Optional.of(failing) : Optional.empty();
        Optional<Paper> unreadable = aggregate ? Optional.empty() : Optional.of(failing);
        List<String> events = new ArrayList<>();
        try (MachineRegistry registry = registry(papers(submit, refused), unreadable, events)) {
            TransitionAbortedException thrown = assertThrows(TransitionAbortedException.class,
                    () -> registry.of(PAPERS).execute(root, Move.SUBMIT));
            assertEquals(aggregate, thrown instanceof CascadeException);
            assertTrue(thrown.getMessage().startsWith("transition aborted in Paper/Stage DRAFT -SUBMIT-> REVIEW: "),
                    thrown.getMessage());
        }
        assertEquals(1, caught.size());
        assertInstanceOf(TransitionAbortedException.class, caught.get(0));
        assertEquals(List.of(Stage.DRAFT, Stage.REVIEW, Stage.REVIEW),
                List.of(root.stage(), good.stage(), failing.stage()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"runtime", "error"})
    void aRootStepThatFailsWhileNoStageCanBePrintedEndsAsAFailureTheObserversHear(String kind) {
        Paper root = new Paper("root", Stage.DRAFT);
        IllegalArgumentException guardFailure = new IllegalArgumentException("the guard failed");
        GraphDefinition<Paper, Stage, Move> graph = StateGraph.define(PAPERS).initial(Stage.DRAFT)
                .terminal(Stage.ARCHIVED).statePolicy(Stage.DRAFT, "a draft is submitted")
                .statePolicy(Stage.REVIEW, "a review is published").statePolicy(Stage.PUBLISHED, "archived")
                .transition(Move.SUBMIT, Stage.DRAFT, Stage.REVIEW)
                    .guard("Broken", (GuardContext<Paper, Stage, Move> ctx) -> {
                        Stage.unprintable = Optional.of(failure(kind));
                        throw guardFailure;
                    })
                    .endTransition()
                .transition(Move.PUBLISH, Stage.REVIEW, Stage.PUBLISHED).endTransition()
                .transition(Move.ARCHIVE, Stage.PUBLISHED, Stage.ARCHIVED).endTransition()
                .build();
        List<String> events = new ArrayList<>();
        try (MachineRegistry registry = registry(graph, Optional.empty(), events)) {
            TransitionAbortedException thrown = assertThrows(TransitionAbortedException.class,
                    () -> registry.of(PAPERS).execute(root, Move.SUBMIT));
            assertSame(guardFailure, thrown.getCause());
        } finally {
            Stage.unprintable = Optional.empty();
        }
        assertEquals(List.of("started", "failed TransitionAbortedException"), events);
        assertEquals(Stage.DRAFT, root.stage());
    }

    @Test
    void anAbortNamesTheConstantsAndNotTheirDisplayText() {
        Paper root = new Paper("root", Stage.DRAFT);
        Paper failing = new Paper("failing", Stage.REVIEW);
        List<String> events = new ArrayList<>();
        try (MachineRegistry registry = registry(papers((TransitionContext<Paper, Stage, Move> ctx) ->
                ctx.cascade(PAPERS, failing, Move.PUBLISH), Optional.empty()), Optional.of(failing), events)) {
            TransitionAbortedException thrown = assertThrows(TransitionAbortedException.class,
                    () -> registry.of(PAPERS).execute(root, Move.SUBMIT));
            assertEquals("transition aborted in Paper/Stage DRAFT -SUBMIT-> REVIEW: the cascade to Paper/Stage failed:"
                    + " java.lang.IllegalArgumentException: the state of failing cannot be read"
                    + " [route Paper/Stage#root]",
                    thrown.getMessage());
        }
    }

    @Test
    void aCascadeCycleNamesBothIdentifiers() {
        Paper root = new Paper("root", Stage.DRAFT);
        List<String> events = new ArrayList<>();
        try (MachineRegistry registry = registry(papers((TransitionContext<Paper, Stage, Move> ctx) ->
                ctx.cascade(PAPERS, root, Move.PUBLISH), Optional.empty()), Optional.empty(), events)) {
            CascadeCycleException thrown = assertThrows(CascadeCycleException.class,
                    () -> registry.of(PAPERS).execute(root, Move.SUBMIT));
            assertTrue(thrown.getMessage().contains("; in flight running SUBMIT and requested PUBLISH"),
                    thrown.getMessage());
        }
    }

    @Test
    void aCommitConflictNamesTheStateItExpected() {
        Paper root = new Paper("root", Stage.DRAFT);
        List<String> events = new ArrayList<>();
        try (MachineRegistry registry = registry(papers((TransitionContext<Paper, Stage, Move> ctx) ->
                root.setStage(Stage.PUBLISHED), Optional.empty()), Optional.empty(), events)) {
            CommitFailedException thrown = assertThrows(CommitFailedException.class,
                    () -> registry.of(PAPERS).execute(root, Move.SUBMIT));
            assertTrue(thrown.getMessage().contains("expected DRAFT but is PUBLISHED"), thrown.getMessage());
        }
    }

    @Test
    void aRejectionInsideAnExitNamesTheNodeOfTheExit() {
        GraphDefinition<Paper, Seal, SealMove> seals = StateGraph.define(SEALS).initial(Seal.OPEN)
                .terminal(Seal.SEALED).statePolicy(Seal.OPEN, "a seal is closed")
                .transition(SealMove.CLOSE, Seal.OPEN, Seal.SEALED).endTransition()
                .build();
        GraphDefinition<Paper, Stage, Move> papers = StateGraph.define(PAPERS).initial(Stage.DRAFT)
                .terminal(Stage.ARCHIVED).statePolicy(Stage.DRAFT, "a draft is submitted")
                .statePolicy(Stage.REVIEW, "a review is published once its seal is closed")
                .statePolicy(Stage.PUBLISHED, "a publication is archived")
                .transition(Move.SUBMIT, Stage.DRAFT, Stage.REVIEW).endTransition()
                .transition(Move.PUBLISH, Stage.REVIEW, Stage.PUBLISHED)
                    .guard("Approved", (GuardContext<Paper, Stage, Move> ctx) -> GuardResult.reject("not approved"))
                    .endTransition()
                .transition(Move.ARCHIVE, Stage.PUBLISHED, Stage.ARCHIVED).endTransition()
                .submachine(Stage.REVIEW, seals)
                    .exit(Seal.SEALED, Move.PUBLISH)
                    .endSubmachine()
                .build();
        Paper paper = new Paper("p1", Stage.REVIEW);
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(papers).state(Paper::stage, Paper::setStage).identity(Paper::id))
                .add(StateMachines.from(seals).state(Paper::seal, Paper::setSeal).identity(Paper::id))
                .build()) {
            TransitionAbortedException thrown = assertThrows(TransitionAbortedException.class,
                    () -> registry.of(SEALS).execute(paper, SealMove.CLOSE));
            assertTrue(thrown.getMessage().contains(
                    "rejected inside the exit of REVIEW by guard Approved: not approved"), thrown.getMessage());
        }
        assertEquals(List.of(Stage.REVIEW, Seal.OPEN), List.of(paper.stage(), paper.seal()));
    }

    @Test
    void aReservedExitRejectionNamesItsIdentifierAndItsNode() {
        GraphDefinition<Paper, Seal, SealMove> seals = StateGraph.define(SEALS).initial(Seal.OPEN)
                .terminal(Seal.SEALED).statePolicy(Seal.OPEN, "a seal is closed")
                .transition(SealMove.CLOSE, Seal.OPEN, Seal.SEALED).endTransition()
                .build();
        GraphDefinition<Paper, Stage, Move> papers = StateGraph.define(PAPERS).initial(Stage.DRAFT)
                .terminal(Stage.ARCHIVED).statePolicy(Stage.DRAFT, "a draft is submitted")
                .statePolicy(Stage.REVIEW, "a review is published once its seal is closed")
                .statePolicy(Stage.PUBLISHED, "a publication is archived")
                .transition(Move.SUBMIT, Stage.DRAFT, Stage.REVIEW).endTransition()
                .transition(Move.PUBLISH, Stage.REVIEW, Stage.PUBLISHED).endTransition()
                .transition(Move.ARCHIVE, Stage.PUBLISHED, Stage.ARCHIVED).endTransition()
                .submachine(Stage.REVIEW, seals)
                    .exit(Seal.SEALED, Move.PUBLISH)
                    .endSubmachine()
                .build();
        Paper paper = new Paper("p1", Stage.REVIEW);
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(papers).state(Paper::stage, Paper::setStage).identity(Paper::id))
                .add(StateMachines.from(seals).state(Paper::seal, Paper::setSeal).identity(Paper::id))
                .build()) {
            Outcome<Paper, Stage, Move> outcome = registry.of(PAPERS).execute(paper, Move.PUBLISH);
            assertEquals(OutcomeCode.RESERVED_EXIT, outcome.code());
            assertEquals("PUBLISH leaves REVIEW only when its sub-machine ends",
                    ((Outcome.Rejected<Paper, Stage, Move>) outcome).reason());
        }
        assertEquals(Stage.REVIEW, paper.stage());
    }
}
