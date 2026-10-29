package consumer;

import static consumer.GraphAssertionErrors.assertFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.AutomaticStep;
import io.github.graphmaquina.api.FlowView;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.runtime.MachineRegistry;
import io.github.graphmaquina.runtime.StateMachines;
import io.github.graphmaquina.test.AbortedAutomaticStepAssert;
import io.github.graphmaquina.test.AutomaticStepAssert;
import io.github.graphmaquina.test.AutomaticStepsAssert;
import io.github.graphmaquina.test.GraphAssertionError;
import io.github.graphmaquina.test.OutcomeAssert;
import io.github.graphmaquina.test.TypedAutomaticStepsAssert;
import io.github.graphmaquina.test.fixtures.TestDomain;
import io.github.graphmaquina.test.fixtures.TestDomain.Entity;
import io.github.graphmaquina.test.fixtures.TestDomain.Id;
import io.github.graphmaquina.test.fixtures.TestDomain.State;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Contract of the automatic step views, which have the shape of the reaction views. An exception thrown by an attempt
 * never propagates to the commit that triggered it, so the two questions a test asks, "did the steps of this commit
 * abort?" and "did anything abort anywhere below it?", are kept apart and each is pinned here. Selection is by entity
 * identity rather than equality, an outcome published untyped is never cast without checking it against the key, and
 * every public expectation whose subject can be null gets its own null-subject case. The searches the outcome view
 * offers over the steps are in {@link AutomaticOutcomeAssertionsTest}.
 */
class AutomaticStepsAssertionsTest {

    private static final String STEPS = "automatic steps";
    private static final String ABORTED = "aborted automatic step";

    // --- the premise: the direct and the recursive check have different scopes ---------------------

    @Test
    void directAndRecursiveChecksHaveDifferentScopes() {
        Entity entity = new Entity("e", State.READY);
        AutomaticStep.Aborted aborted =
                new AutomaticStep.Aborted(TestDomain.KEY, entity, new IllegalStateException("down"));
        List<AutomaticStep> steps = List.of(done(entity, withSteps(entity, aborted)));
        AutomaticStepsAssert.assertThat(steps).hasNoAborted();
        assertThrows(GraphAssertionError.class, () -> AutomaticStepsAssert.assertThat(steps).hasNoAbortedRecursively());
        AutomaticStepsAssert.assertThat(steps).forEntity(TestDomain.KEY, entity).single().isDone()
                .outcome().isApplied().movedTo(State.READY);
    }

    // --- the untyped list ----------------------------------------------------------------------------

    @Test
    void sizeIsReadOffTheWholeList() {
        Entity entity = new Entity("e", State.READY);
        AutomaticStepsAssert view = AutomaticStepsAssert.assertThat(List.of(done(entity, applied(entity)),
                new AutomaticStep.Aborted(TestDomain.KEY, entity, new IllegalStateException("down"))));
        assertSame(view, view.hasSize(2));
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasSize(1)), STEPS, "size", 1, 2);
    }

    @Test
    void anEmptyListSatisfiesOnlyIsEmpty() {
        Entity entity = new Entity("e", State.READY);
        AutomaticStepsAssert empty = AutomaticStepsAssert.assertThat(List.of());
        assertSame(empty, empty.isEmpty());
        empty.hasNoAborted().hasNoAbortedRecursively();
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> AutomaticStepsAssert.assertThat(List.of(done(entity, applied(entity)))).isEmpty()),
                STEPS, "size", 0, 1);
    }

    @Test
    void theViewCopiesTheListItWasGiven() {
        Entity entity = new Entity("e", State.READY);
        List<AutomaticStep> mutable = new ArrayList<>();
        mutable.add(done(entity, applied(entity)));
        AutomaticStepsAssert view = AutomaticStepsAssert.assertThat(mutable);
        mutable.add(new AutomaticStep.Aborted(TestDomain.KEY, entity, new IllegalStateException("down")));
        mutable.clear();
        view.hasSize(1);
        view.hasNoAborted();
        view.forEntity(TestDomain.KEY, entity).hasSize(1);
    }

    @Test
    void hasNoAbortedNamesThePositionAndCarriesTheCause() {
        Entity entity = new Entity("e", State.READY);
        IllegalStateException cause = new IllegalStateException("down");
        AutomaticStepsAssert view = AutomaticStepsAssert.assertThat(List.of(done(entity, applied(entity)),
                new AutomaticStep.Aborted(TestDomain.KEY, entity, cause)));
        GraphAssertionError error = assertThrows(GraphAssertionError.class, view::hasNoAborted);
        assertFailure(error, STEPS + "[1]", ABORTED, "none", cause);
        assertSame(cause, error.getCause());
    }

    @Test
    void hasNoAbortedRecursivelyNamesThePathDownToTheNearestAbort() {
        Entity entity = new Entity("e", State.READY);
        IllegalStateException cause = new IllegalStateException("down");
        AutomaticStep.Aborted aborted = new AutomaticStep.Aborted(TestDomain.KEY, entity, cause);
        Outcome<Entity, State, Id> inner = withSteps(entity, done(entity, applied(entity)), aborted);
        AutomaticStepsAssert view = AutomaticStepsAssert.assertThat(List.of(done(entity, inner)));
        GraphAssertionError error = assertThrows(GraphAssertionError.class, view::hasNoAbortedRecursively);
        assertFailure(error, STEPS + "[0].outcome.automaticSteps[1]", ABORTED, "none", cause);
        assertSame(cause, error.getCause());
    }

    @Test
    void hasNoAbortedRecursivelyHoldsForATreeWithoutAbortsAndVisitsASharedOutcomeOnce() {
        Entity entity = new Entity("e", State.READY);
        Outcome<Entity, State, Id> level = applied(entity);
        for (int depth = 0; depth < 40; depth++) {
            AutomaticStep child = done(entity, level);
            level = withSteps(entity, child, child);
        }
        AutomaticStepsAssert view = AutomaticStepsAssert.assertThat(List.of(done(entity, level)));
        assertSame(view, view.hasNoAbortedRecursively());
    }

    @Test
    void forEntitySelectsByKeyAndIdentityNeverByEquality() {
        Entity entity = new Entity("e", State.READY);
        Entity other = new Entity("e", State.READY);
        AutomaticStepsAssert view = AutomaticStepsAssert.assertThat(List.of(done(entity, applied(entity)),
                done(other, applied(other)), done(entity, applied(entity))));
        view.forEntity(TestDomain.KEY, entity).hasSize(2);
        view.forEntity(TestDomain.KEY, other).hasSize(1);
        view.forEntity(TestDomain.KEY, new Entity("e", State.READY)).hasSize(0);
    }

    @Test
    void aNullStepInTheSubjectIsNeverACrash() {
        Entity entity = new Entity("e", State.READY);
        AutomaticStepsAssert view = AutomaticStepsAssert.assertThat(
                new ArrayList<>(Arrays.asList(done(entity, applied(entity)), null)));
        assertSame(view, view.hasSize(2));
        assertSame(view, view.hasNoAborted());
        assertSame(view, view.hasNoAbortedRecursively());
        view.forEntity(TestDomain.KEY, entity).single().isDone().outcome().isApplied().movedTo(State.READY);
    }

    // --- one entity ----------------------------------------------------------------------------------

    @Test
    void theTypedViewCountsAndIndexesTheStepsOfOneEntity() {
        Entity entity = new Entity("e", State.READY);
        TypedAutomaticStepsAssert<Entity, State, Id> view = AutomaticStepsAssert.assertThat(List.of(
                        done(entity, applied(entity)),
                        new AutomaticStep.Aborted(TestDomain.KEY, entity, new IllegalStateException("down"))))
                .forEntity(TestDomain.KEY, entity);
        assertSame(view, view.hasSize(2));
        view.atIndex(0).isDone();
        view.atIndex(1).isAborted();
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasSize(1)),
                STEPS + " for " + TestDomain.KEY, "size", 1, 2);
    }

    @Test
    void singleFailsOnAnyOtherSizeAndAtIndexOnAMissingOrNegativeIndex() {
        Entity entity = new Entity("e", State.READY);
        TypedAutomaticStepsAssert<Entity, State, Id> two = AutomaticStepsAssert.assertThat(
                List.of(done(entity, applied(entity)), done(entity, applied(entity))))
                .forEntity(TestDomain.KEY, entity);
        String context = STEPS + " for " + TestDomain.KEY;
        assertFailure(assertThrows(GraphAssertionError.class, two::single), context, "size", 1, 2);
        assertFailure(assertThrows(GraphAssertionError.class, () -> two.atIndex(2)),
                context, "index 2 present", Boolean.TRUE, Boolean.FALSE);
        assertEquals("index must not be negative: -1",
                assertThrows(IllegalArgumentException.class, () -> two.atIndex(-1)).getMessage());
    }

    @Test
    void aStepIsDoneOrAbortedAndTheWrongNarrowingFailsOnTheKind() {
        Entity entity = new Entity("e", State.READY);
        AutomaticStepAssert<Entity, State, Id> done = single(done(entity, applied(entity)), entity);
        AutomaticStepAssert<Entity, State, Id> aborted = single(
                new AutomaticStep.Aborted(TestDomain.KEY, entity, new IllegalStateException("down")), entity);
        String context = STEPS + " for " + TestDomain.KEY + "[0]";
        done.isDone();
        aborted.isAborted();
        assertFailure(assertThrows(GraphAssertionError.class, done::isAborted), context, "kind", "Aborted", "Done");
        assertFailure(assertThrows(GraphAssertionError.class, aborted::isDone), context, "kind", "Done", "Aborted");
    }

    @Test
    void theOutcomeOfADoneStepIsNarrowedToTheMachineAndNeverCastBlindly() {
        Entity entity = new Entity("e", State.READY);
        single(done(entity, applied(entity)), entity).isDone().outcome().isApplied().hasEntitySameAs(entity);
        Outcome<?, ?, ?> foreign = new Outcome.Applied<>("other", Other.A, Other.GO, Other.B, List.of(), List.of());
        AutomaticStepAssert<Entity, State, Id> mismatched = single(done(entity, foreign), entity);
        assertThrows(IllegalArgumentException.class, () -> mismatched.isDone().outcome());
    }

    @Test
    void anAbortedStepMatchesItsCauseByTypeAndByReference() {
        Entity entity = new Entity("e", State.READY);
        IllegalStateException cause = new IllegalStateException("down");
        AbortedAutomaticStepAssert<Entity, State, Id> view =
                single(new AutomaticStep.Aborted(TestDomain.KEY, entity, cause), entity).isAborted();
        String context = STEPS + " for " + TestDomain.KEY + "[0]";
        assertSame(view, view.hasCause(IllegalStateException.class).hasCause(RuntimeException.class));
        assertSame(view, view.hasCauseSameAs(cause));
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasCause(IllegalArgumentException.class)),
                context, "cause", IllegalArgumentException.class, cause);
        IllegalStateException twin = new IllegalStateException("down");
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasCauseSameAs(twin)),
                context, "cause", twin, cause);
    }

    // --- null subjects: one case per expectation whose subject can be absent -----------------------

    @Test
    void nullSubjectFailsHasSizeAndIsEmpty() {
        List<AutomaticStep> none = null;
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> AutomaticStepsAssert.assertThat(none).hasSize(1)), STEPS, "size", 1, null);
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> AutomaticStepsAssert.assertThat(none).isEmpty()), STEPS, "size", 0, null);
    }

    @Test
    void nullSubjectFailsBothAbortChecksAndForEntity() {
        List<AutomaticStep> none = null;
        Entity entity = new Entity("e", State.READY);
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> AutomaticStepsAssert.assertThat(none).hasNoAborted()), STEPS, ABORTED, "none", null);
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> AutomaticStepsAssert.assertThat(none).hasNoAbortedRecursively()),
                STEPS, ABORTED, "none", null);
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> AutomaticStepsAssert.assertThat(none).forEntity(TestDomain.KEY, entity)),
                STEPS, "entity automatic steps", entity, null);
    }

    @Test
    void aNullRequiredArgumentIsAnArgumentError() {
        Entity entity = new Entity("e", State.READY);
        AutomaticStepsAssert view = AutomaticStepsAssert.assertThat(List.of());
        assertEquals("key is marked non-null but is null",
                assertThrows(NullPointerException.class, () -> view.forEntity(null, entity)).getMessage());
        assertEquals("entity is marked non-null but is null",
                assertThrows(NullPointerException.class, () -> view.forEntity(TestDomain.KEY, null)).getMessage());
        AbortedAutomaticStepAssert<Entity, State, Id> aborted = single(
                new AutomaticStep.Aborted(TestDomain.KEY, entity, new IllegalStateException("down")), entity)
                .isAborted();
        assertEquals("expected is marked non-null but is null",
                assertThrows(NullPointerException.class, () -> aborted.hasCause(null)).getMessage());
        assertEquals("expected is marked non-null but is null",
                assertThrows(NullPointerException.class, () -> aborted.hasCauseSameAs(null)).getMessage());
    }

    // --- against the real engine ---------------------------------------------------------------------

    /**
     * An applied PREPARE leaves the entity in READY, whose only automatic edge finishes it: the chain is one step,
     * done, and its outcome is the applied FINISH.
     */
    @Test
    void anAutomaticChainOfTheEngineIsReadThroughTheView() {
        Entity entity = new Entity("e1", State.NEW);
        try (MachineRegistry registry = MachineRegistry.builder().add(StateMachines.from(
                        StateGraph.define(TestDomain.KEY).initial(State.NEW).terminal(State.DONE)
                                .terminal(State.FAILED)
                                .statePolicy(State.NEW, "prepare first").statePolicy(State.READY, "finish only")
                                .transition(Id.PREPARE, State.NEW, State.READY).endTransition()
                                .transition(Id.RECOVER, State.NEW, State.FAILED).endTransition()
                                .transition(Id.FINISH, State.READY, State.DONE).automatic()
                                    .action("finish", (TransitionContext<Entity, State, Id> ctx) ->
                                            ActionOutcome.ok()).endTransition()
                                .build())
                        .state(Entity::state, Entity::setState).identity(Entity::id)).build()) {
            Outcome<Entity, State, Id> outcome = registry.of(TestDomain.KEY).execute(entity, Id.PREPARE);
            AutomaticStepsAssert steps = OutcomeAssert.assertThat(outcome).isApplied().automaticSteps();
            steps.hasSize(1).hasNoAbortedRecursively();
            steps.forEntity(TestDomain.KEY, entity).single().isDone().outcome().isApplied()
                    .from(State.READY).requestedTransition(Id.FINISH).movedTo(State.DONE);
        }
    }

    // --- fixtures ------------------------------------------------------------------------------------

    /** States and identifiers of a machine that is not the test domain's. */
    enum Other { A, B, GO }

    private static Outcome.Applied<Entity, State, Id> applied(Entity entity) {
        return new Outcome.Applied<>(entity, State.NEW, Id.PREPARE, State.READY, List.of(), List.of());
    }

    private static Outcome<Entity, State, Id> withSteps(Entity entity, AutomaticStep... steps) {
        return new Outcome.Applied<>(entity, State.NEW, Id.PREPARE, State.READY, List.of(), List.of(), List.of(),
                FlowView.empty(), List.of(steps));
    }

    private static AutomaticStep.Done done(Object entity, Outcome<?, ?, ?> outcome) {
        return new AutomaticStep.Done(TestDomain.KEY, entity, outcome);
    }

    private static AutomaticStepAssert<Entity, State, Id> single(AutomaticStep step, Entity entity) {
        return AutomaticStepsAssert.assertThat(List.of(step)).forEntity(TestDomain.KEY, entity).single();
    }
}
