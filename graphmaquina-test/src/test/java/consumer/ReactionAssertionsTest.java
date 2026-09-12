package consumer;

import static io.github.graphmaquina.api.WatchCondition.allIn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.Reaction;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.TransitionRecord;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.error.AfterCommitException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.error.UninitializedStateException;
import io.github.graphmaquina.runtime.MachineRegistry;
import io.github.graphmaquina.runtime.StateMachines;
import io.github.graphmaquina.test.AbortedReactionAssert;
import io.github.graphmaquina.test.GraphAssertionError;
import io.github.graphmaquina.test.OutcomeAssert;
import io.github.graphmaquina.test.ReactionAssert;
import io.github.graphmaquina.test.ReactionsAssert;
import io.github.graphmaquina.test.TypedReactionsAssert;
import io.github.graphmaquina.test.fixtures.TestDomain;
import io.github.graphmaquina.test.fixtures.TestDomain.Entity;
import io.github.graphmaquina.test.fixtures.TestDomain.State;
import io.github.graphmaquina.test.internal.CheckedTypes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Contract of the reaction views. A reaction never propagates its failure to the commit that triggered it,
 * so the two questions a test asks — "did this commit's own re-evaluations abort?" and "did anything abort
 * anywhere below it?" — are kept apart and each is pinned here. Selection is by entity identity rather than
 * equality, an outcome published untyped is never cast without checking it against the key, every mismatch
 * is read off the fields of the {@link GraphAssertionError} rather than off its type alone, and every public
 * expectation whose subject can be null gets its own null-subject case.
 */
class ReactionAssertionsTest {

    // --- the premise of this task: the direct and the recursive check have different scopes ---------

    @Test
    void directAndRecursiveChecksHaveDifferentScopes() {
        Entity entity = new Entity("e", State.READY);
        Reaction.Aborted aborted =
                new Reaction.Aborted(TestDomain.KEY, entity, new IllegalStateException("down"));
        Outcome.Applied<Entity, State> nested = new Outcome.Applied<>(entity, State.NEW, State.READY,
                List.of(), List.of(aborted));
        List<Reaction> reactions = List.of(new Reaction.Done(TestDomain.KEY, entity, nested));
        ReactionsAssert.assertThat(reactions).hasNoAborted();
        assertThrows(GraphAssertionError.class,
                () -> ReactionsAssert.assertThat(reactions).hasNoAbortedRecursively());
        ReactionsAssert.assertThat(reactions).forEntity(TestDomain.KEY, entity).single().isDone()
                .outcome().isApplied().movedTo(State.READY);
    }

    // --- the untyped list --------------------------------------------------------------------------

    @Test
    void sizeIsReadOffTheWholeList() {
        Entity entity = new Entity("e", State.READY);
        ReactionsAssert view = ReactionsAssert.assertThat(List.of(done(entity, applied(entity)),
                new Reaction.Aborted(TestDomain.KEY, entity, new IllegalStateException("down"))));
        assertSame(view, view.hasSize(2));
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasSize(1)),
                "reactions", "size", 1, 2);
    }

    @Test
    void anEmptyListSatisfiesOnlyIsEmpty() {
        Entity entity = new Entity("e", State.READY);
        ReactionsAssert empty = ReactionsAssert.assertThat(List.of());
        assertSame(empty, empty.isEmpty());
        empty.hasNoAborted().hasNoAbortedRecursively();
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> ReactionsAssert.assertThat(List.of(done(entity, applied(entity)))).isEmpty()),
                "reactions", "size", 0, 1);
    }

    @Test
    void theViewCopiesTheListItWasGiven() {
        Entity entity = new Entity("e", State.READY);
        List<Reaction> mutable = new ArrayList<>();
        mutable.add(done(entity, applied(entity)));
        ReactionsAssert view = ReactionsAssert.assertThat(mutable);
        mutable.add(new Reaction.Aborted(TestDomain.KEY, entity, new IllegalStateException("down")));
        mutable.clear();
        view.hasSize(1);
        view.hasNoAborted();
        view.forEntity(TestDomain.KEY, entity).hasSize(1);
    }

    // --- aborted: the direct check ------------------------------------------------------------------

    @Test
    void hasNoAbortedNamesThePositionAndCarriesTheCause() {
        Entity entity = new Entity("e", State.READY);
        IllegalStateException cause = new IllegalStateException("down");
        ReactionsAssert view = ReactionsAssert.assertThat(List.of(done(entity, applied(entity)),
                new Reaction.Aborted(TestDomain.KEY, entity, cause)));
        GraphAssertionError error = assertThrows(GraphAssertionError.class, view::hasNoAborted);
        assertEquals("reactions[1]", error.context());
        assertEquals("aborted reaction", error.property());
        assertEquals("none", error.expected());
        assertSame(cause, error.actual());
    }

    @Test
    void hasNoAbortedIgnoresAnAbortThatOnlyTheRecursiveCheckSees() {
        Entity entity = new Entity("e", State.READY);
        Reaction.Aborted deep =
                new Reaction.Aborted(TestDomain.KEY, entity, new IllegalStateException("deep"));
        Outcome.Failed<Entity, State> failed = new Outcome.Failed<>(entity, State.NEW, State.READY,
                State.FAILED, "boom", Optional.empty(), List.of(), List.of(deep));
        ReactionsAssert view = ReactionsAssert.assertThat(List.of(done(entity, failed)));
        assertSame(view, view.hasNoAborted());
        assertFailure(assertThrows(GraphAssertionError.class, view::hasNoAbortedRecursively),
                "reactions[0].outcome.reactions[0]", "aborted reaction", "none", deep.cause());
    }

    // --- aborted: the recursive check ---------------------------------------------------------------

    @Test
    void hasNoAbortedRecursivelyNamesTheWholePathToTheAbort() {
        Entity entity = new Entity("e", State.READY);
        Reaction.Aborted deepest =
                new Reaction.Aborted(TestDomain.KEY, entity, new IllegalStateException("deepest"));
        Outcome.Applied<Entity, State> grandchild = new Outcome.Applied<>(entity, State.NEW, State.READY,
                List.of(), List.of(deepest));
        Outcome.Applied<Entity, State> child = new Outcome.Applied<>(entity, State.NEW, State.READY,
                List.of(), List.of(done(entity, grandchild)));
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> ReactionsAssert.assertThat(List.of(done(entity, child))).hasNoAbortedRecursively()),
                "reactions[0].outcome.reactions[0].outcome.reactions[0]",
                "aborted reaction", "none", deepest.cause());
    }

    @Test
    void hasNoAbortedRecursivelyReportsTheAbortNearestTheRoot() {
        Entity entity = new Entity("e", State.READY);
        Reaction.Aborted deep =
                new Reaction.Aborted(TestDomain.KEY, entity, new IllegalStateException("deep"));
        Outcome.Applied<Entity, State> child = new Outcome.Applied<>(entity, State.NEW, State.READY,
                List.of(), List.of(deep));
        Reaction.Aborted shallow =
                new Reaction.Aborted(TestDomain.KEY, entity, new IllegalStateException("shallow"));
        assertFailure(assertThrows(GraphAssertionError.class, () -> ReactionsAssert
                        .assertThat(List.of(done(entity, child), shallow)).hasNoAbortedRecursively()),
                "reactions[1]", "aborted reaction", "none", shallow.cause());
    }

    @Test
    void hasNoAbortedRecursivelyKeepsTheOrderOfTheReactionsThemselves() {
        Entity entity = new Entity("e", State.READY);
        Reaction.Aborted underTheFirst =
                new Reaction.Aborted(TestDomain.KEY, entity, new IllegalStateException("first"));
        Reaction.Aborted underTheSecond =
                new Reaction.Aborted(TestDomain.KEY, entity, new IllegalStateException("second"));
        Outcome.Applied<Entity, State> left = new Outcome.Applied<>(entity, State.NEW, State.READY,
                List.of(), List.of(underTheFirst));
        Outcome.Applied<Entity, State> right = new Outcome.Applied<>(entity, State.NEW, State.READY,
                List.of(), List.of(underTheSecond));
        assertFailure(assertThrows(GraphAssertionError.class, () -> ReactionsAssert
                        .assertThat(List.of(done(entity, left), done(entity, right)))
                        .hasNoAbortedRecursively()),
                "reactions[0].outcome.reactions[0]", "aborted reaction", "none", underTheFirst.cause());
    }

    @Test
    void anIgnoredOrRejectedReactionOutcomeCarriesNoDescendants() {
        Entity entity = new Entity("e", State.READY);
        ReactionsAssert view = ReactionsAssert.assertThat(List.of(
                done(entity, new Outcome.Ignored<>(entity, State.READY, State.DONE)),
                done(entity, new Outcome.Rejected<>(entity, State.READY, State.NEW, "forbidden",
                        Optional.empty()))));
        assertSame(view, view.hasNoAborted());
        assertSame(view, view.hasNoAbortedRecursively());
    }

    @Test
    void anOutcomeSharedByTwoReactionsIsReportedAtItsFirstOccurrence() {
        Entity entity = new Entity("e", State.READY);
        Reaction.Aborted deep =
                new Reaction.Aborted(TestDomain.KEY, entity, new IllegalStateException("deep"));
        Outcome.Applied<Entity, State> shared = new Outcome.Applied<>(entity, State.NEW, State.READY,
                List.of(), List.of(deep));
        assertFailure(assertThrows(GraphAssertionError.class, () -> ReactionsAssert
                        .assertThat(List.of(done(entity, shared), done(entity, shared)))
                        .hasNoAbortedRecursively()),
                "reactions[0].outcome.reactions[0]", "aborted reaction", "none", deep.cause());
    }

    /**
     * The identity set is a bound on work, and work is the only thing that can observe it: two outcomes
     * that are {@code equals} are records whose reaction lists are equal element by element, so their
     * subtrees are isomorphic and no path, cause or verdict can tell "descended twice" from "descended
     * once". A shared-outcome chain is where the difference becomes measurable. Thirty levels, each one a
     * pair of reactions over the SAME child outcome, is 31 outcomes to visit with the identity set and
     * 2^30 frames without it; and a {@code HashSet} in its place is no better, because hashing one of
     * these outcomes walks the same doubling tree. Either mutation turns this assertion into a hang, so
     * the deadline is what kills them.
     */
    @Test
    void aSharedOutcomeIsDescendedIntoOnceSoADeepSharedGraphStaysLinear() {
        Entity entity = new Entity("e", State.READY);
        List<Reaction> reactions = List.of(done(entity, sharedChain(entity, 30)));
        assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> ReactionsAssert.assertThat(reactions).hasNoAbortedRecursively());
    }

    // --- selection ----------------------------------------------------------------------------------

    @Test
    void selectionIsByEntityIdentityAndByKey() {
        Entity entity = new Entity("same-id", State.READY);
        Entity twin = new Entity("same-id", State.READY);
        Other other = new Other("o");
        List<Reaction> reactions = List.of(done(entity, applied(entity)),
                new Reaction.Done(OTHERS, other, new Outcome.Ignored<>(other, Flag.OFF, Flag.ON)));
        ReactionsAssert view = ReactionsAssert.assertThat(reactions);
        view.hasSize(2);
        view.forEntity(TestDomain.KEY, entity).single().isDone().outcome().isApplied().movedTo(State.READY);
        view.forEntity(OTHERS, other).single().isDone().outcome().isIgnored().from(Flag.OFF);
        view.forEntity(TestDomain.KEY, twin).hasSize(0);
    }

    @Test
    void selectionIsByReferenceNotByEquality() {
        Other other = new Other("o");
        Other twin = new Other("o");
        assertEquals(other, twin);
        assertNotSame(other, twin);
        ReactionsAssert view = ReactionsAssert.assertThat(List.of(
                new Reaction.Done(OTHERS, other, new Outcome.Ignored<>(other, Flag.OFF, Flag.ON))));
        view.forEntity(OTHERS, other).single().isDone().outcome().isIgnored().from(Flag.OFF);
        view.forEntity(OTHERS, twin).hasSize(0);
    }

    @Test
    void selectionSeparatesTwoMachinesOverTheSameEntityInstance() {
        Entity entity = new Entity("e", State.READY);
        List<Reaction> both = List.of(done(entity, applied(entity)),
                new Reaction.Done(SHELVES, entity, new Outcome.Ignored<>(entity, Shelf.OUT, Shelf.IN)));
        ReactionsAssert view = ReactionsAssert.assertThat(both);
        view.hasSize(2);
        view.forEntity(TestDomain.KEY, entity).single().isDone().outcome()
                .isApplied().movedTo(State.READY);
        view.forEntity(SHELVES, entity).single().isDone().outcome().isIgnored().from(Shelf.OUT);
    }

    @Test
    void anEntityWithNoReactionsSelectsAnEmptyViewRatherThanFailing() {
        Entity entity = new Entity("e", State.READY);
        Entity absent = new Entity("absent", State.READY);
        TypedReactionsAssert<Entity, State> selected = ReactionsAssert
                .assertThat(List.of(done(entity, applied(entity)))).forEntity(TestDomain.KEY, absent);
        assertSame(selected, selected.hasSize(0));
        assertFailure(assertThrows(GraphAssertionError.class, selected::single),
                "reactions for " + TestDomain.KEY, "size", 1, 0);
    }

    @Test
    void selectionKeepsDuplicatesAndTheirOrder() {
        Entity entity = new Entity("e", State.READY);
        Reaction.Aborted aborted =
                new Reaction.Aborted(TestDomain.KEY, entity, new IllegalStateException("down"));
        TypedReactionsAssert<Entity, State> selected = ReactionsAssert
                .assertThat(List.of(done(entity, applied(entity)), aborted,
                        done(entity, new Outcome.Ignored<>(entity, State.READY, State.DONE))))
                .forEntity(TestDomain.KEY, entity);
        selected.hasSize(3);
        selected.atIndex(0).isDone().outcome().isApplied().movedTo(State.READY);
        selected.atIndex(1).isAborted().hasCauseSameAs(aborted.cause());
        selected.atIndex(2).isDone().outcome().isIgnored().requestedTarget(State.DONE);
        assertFailure(assertThrows(GraphAssertionError.class, selected::single),
                "reactions for " + TestDomain.KEY, "size", 1, 3);
    }

    @Test
    void anIndexBeyondTheSelectionFailsAsAnAssertion() {
        Entity entity = new Entity("e", State.READY);
        TypedReactionsAssert<Entity, State> selected = ReactionsAssert
                .assertThat(List.of(done(entity, applied(entity)))).forEntity(TestDomain.KEY, entity);
        assertFailure(assertThrows(GraphAssertionError.class, () -> selected.atIndex(1)),
                "reactions for " + TestDomain.KEY, "index 1 present", true, false);
        assertFailure(assertThrows(GraphAssertionError.class, () -> selected.hasSize(2)),
                "reactions for " + TestDomain.KEY, "size", 2, 1);
    }

    @Test
    void aNegativeIndexIsAnArgumentError() {
        Entity entity = new Entity("e", State.READY);
        TypedReactionsAssert<Entity, State> selected = ReactionsAssert
                .assertThat(List.of(done(entity, applied(entity)))).forEntity(TestDomain.KEY, entity);
        assertEquals("index must not be negative: -1",
                assertThrows(IllegalArgumentException.class, () -> selected.atIndex(-1)).getMessage());
    }

    // --- one reaction ---------------------------------------------------------------------------------

    @Test
    void aReactionIsEitherDoneOrAborted() {
        Entity entity = new Entity("e", State.READY);
        String where = "reactions for " + TestDomain.KEY + "[0]";
        ReactionAssert<Entity, State> ok = single(done(entity, applied(entity)), entity);
        assertFailure(assertThrows(GraphAssertionError.class, ok::isAborted),
                where, "kind", "Aborted", "Done");
        ReactionAssert<Entity, State> broken = single(
                new Reaction.Aborted(TestDomain.KEY, entity, new IllegalStateException("down")), entity);
        assertFailure(assertThrows(GraphAssertionError.class, broken::isDone),
                where, "kind", "Done", "Aborted");
    }

    @Test
    void aDoneReactionExposesEveryOutcomeVariant() {
        Entity entity = new Entity("e", State.READY);
        single(done(entity, applied(entity)), entity).isDone().outcome().isApplied()
                .movedTo(State.READY).from(State.NEW).hasEntitySameAs(entity);
        single(done(entity, new Outcome.Ignored<>(entity, State.READY, State.DONE)), entity)
                .isDone().outcome().isIgnored().requestedTarget(State.DONE).hasRecordedState(State.READY);
        single(done(entity, new Outcome.Rejected<>(entity, State.READY, State.NEW, "forbidden",
                Optional.empty())), entity)
                .isDone().outcome().isRejected().isForbidden().hasReason("forbidden");
        single(done(entity, new Outcome.Failed<>(entity, State.NEW, State.READY, State.FAILED, "boom",
                Optional.empty(), List.of(), List.of())), entity)
                .isDone().outcome().isFailed().movedTo(State.FAILED).requestedTarget(State.READY);
    }

    @Test
    void anAbortedReactionComparesItsCauseByTypeAndByReference() {
        Entity entity = new Entity("e", State.READY);
        IllegalStateException cause = new IllegalStateException("down");
        IllegalStateException twin = new IllegalStateException("down");
        AbortedReactionAssert<Entity, State> view =
                single(new Reaction.Aborted(TestDomain.KEY, entity, cause), entity).isAborted();
        String where = "reactions for " + TestDomain.KEY + "[0]";
        assertSame(view, view.hasCause(IllegalStateException.class));
        assertSame(view, view.hasCause(RuntimeException.class));
        assertSame(view, view.hasCauseSameAs(cause));
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> view.hasCause(IllegalArgumentException.class)),
                where, "cause", IllegalArgumentException.class, cause);
        GraphAssertionError error =
                assertThrows(GraphAssertionError.class, () -> view.hasCauseSameAs(twin));
        assertEquals(where, error.context());
        assertEquals("cause", error.property());
        assertSame(twin, error.expected());
        assertSame(cause, error.actual());
    }

    /**
     * {@link IllegalStateException} inherits {@link Object}'s identity equality, so the case above cannot
     * tell a reference comparison from an equality one. A cause that declares every instance of its type
     * equal can: {@code hasCauseSameAs} must still reject the twin.
     */
    @Test
    void hasCauseSameAsRejectsATwinThatCallsItselfEqual() {
        Entity entity = new Entity("e", State.READY);
        EqualCause cause = new EqualCause();
        EqualCause twin = new EqualCause();
        assertEquals(cause, twin);
        assertNotSame(cause, twin);
        AbortedReactionAssert<Entity, State> view =
                single(new Reaction.Aborted(TestDomain.KEY, entity, cause), entity).isAborted();
        assertSame(view, view.hasCauseSameAs(cause));
        GraphAssertionError error =
                assertThrows(GraphAssertionError.class, () -> view.hasCauseSameAs(twin));
        assertEquals("reactions for " + TestDomain.KEY + "[0]", error.context());
        assertEquals("cause", error.property());
        assertSame(twin, error.expected());
        assertSame(cause, error.actual());
    }

    @Test
    void anAfterCommitFailureIsReportedAsTheCauseItIs() {
        Entity entity = new Entity("e", State.READY);
        AfterCommitException cause = new AfterCommitException(TestDomain.KEY, entity, State.NEW,
                State.READY, "audit", new IllegalStateException("the hook threw"));
        AbortedReactionAssert<Entity, State> view =
                single(new Reaction.Aborted(TestDomain.KEY, entity, cause), entity).isAborted();
        view.hasCause(AfterCommitException.class).hasCauseSameAs(cause);
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> view.hasCause(TransitionAbortedException.class)),
                "reactions for " + TestDomain.KEY + "[0]", "cause",
                TransitionAbortedException.class, cause);
    }

    // --- the localized cast ---------------------------------------------------------------------------

    @Test
    void anOutcomeWhoseEntityDoesNotMatchTheKeyIsNeverCast() {
        Other other = new Other("o");
        Outcome<Other, Flag> corrupt = new Outcome.Applied<>(other, Flag.OFF, Flag.ON, List.of(), List.of());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> CheckedTypes.outcome(TestDomain.KEY, corrupt));
        assertEquals("outcome does not belong to the machine: entity expected " + Entity.class
                + " but was " + other, error.getMessage());
    }

    @Test
    void anOutcomeWhoseStatesDoNotMatchTheKeyIsNeverCast() {
        Entity entity = new Entity("e", State.READY);
        Outcome<Entity, Flag> corrupt =
                new Outcome.Applied<>(entity, Flag.OFF, Flag.ON, List.of(), List.of());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> CheckedTypes.outcome(TestDomain.KEY, corrupt));
        assertEquals("outcome does not belong to the machine: states expected " + State.class
                + " but was " + List.of(Flag.OFF, Flag.ON, Flag.ON), error.getMessage());
    }

    /**
     * The three states an outcome names can diverge: a rejected one never moved, so its requested target is
     * neither its origin nor the state it records as current, and a failed one records a third state again.
     * Only a raw construction can carry a foreign enum in one of them, and each is checked on its own.
     */
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void aStateOfAForeignEnumIsNeverCastWhicheverFieldCarriesIt() {
        Entity entity = new Entity("e", State.READY);
        Outcome<?, ?> foreignTarget = (Outcome<?, ?>) new Outcome.Rejected(
                entity, State.NEW, Flag.ON, "forbidden", Optional.empty());
        assertEquals("outcome does not belong to the machine: states expected " + State.class
                        + " but was " + List.of(State.NEW, State.NEW, Flag.ON),
                assertThrows(IllegalArgumentException.class,
                        () -> CheckedTypes.outcome(TestDomain.KEY, foreignTarget)).getMessage());
        Outcome<?, ?> foreignRoute = (Outcome<?, ?>) new Outcome.Failed(
                entity, State.NEW, State.READY, Flag.ON, "boom", Optional.empty(), List.of(), List.of());
        assertEquals("outcome does not belong to the machine: states expected " + State.class
                        + " but was " + List.of(State.NEW, Flag.ON, State.READY),
                assertThrows(IllegalArgumentException.class,
                        () -> CheckedTypes.outcome(TestDomain.KEY, foreignRoute)).getMessage());
    }

    @Test
    void anOutcomeInsideAManuallyBuiltDoneIsCheckedWhenItIsRead() {
        Entity entity = new Entity("e", State.READY);
        Outcome<Entity, Flag> corrupt =
                new Outcome.Applied<>(entity, Flag.OFF, Flag.ON, List.of(), List.of());
        ReactionAssert<Entity, State> view =
                single(new Reaction.Done(TestDomain.KEY, entity, corrupt), entity);
        assertThrows(IllegalArgumentException.class, () -> view.isDone().outcome());
    }

    // --- the two outcome views that open these ones ---------------------------------------------------

    @Test
    void anAppliedOutcomeExposesItsReactions() {
        Entity entity = new Entity("e", State.READY);
        Outcome<Entity, State> outcome = new Outcome.Applied<>(entity, State.NEW, State.READY,
                List.of(), List.of(done(entity, applied(entity))));
        ReactionsAssert reactions = OutcomeAssert.assertThat(outcome).isApplied().reactions();
        assertSame(reactions, reactions.hasSize(1));
        reactions.forEntity(TestDomain.KEY, entity).single().isDone().outcome().isApplied();
    }

    @Test
    void aFailedOutcomeExposesItsReactions() {
        Entity entity = new Entity("e", State.READY);
        List<TransitionRecord<?, ?>> records = List.of();
        Reaction.Aborted aborted =
                new Reaction.Aborted(TestDomain.KEY, entity, new IllegalStateException("down"));
        Outcome<Entity, State> outcome = new Outcome.Failed<>(entity, State.NEW, State.READY, State.FAILED,
                "boom", Optional.empty(), records, List.of(aborted));
        ReactionsAssert reactions = OutcomeAssert.assertThat(outcome).isFailed().reactions();
        reactions.hasSize(1);
        assertThrows(GraphAssertionError.class, reactions::hasNoAborted);
        reactions.forEntity(TestDomain.KEY, entity).single().isAborted()
                .hasCause(IllegalStateException.class);
    }

    @Test
    void anOutcomeWithoutReactionsExposesAnEmptyView() {
        Entity entity = new Entity("e", State.READY);
        Outcome<Entity, State> outcome = new Outcome.Applied<>(entity, State.NEW, State.READY,
                List.of(), List.of());
        OutcomeAssert.assertThat(outcome).isApplied().reactions().isEmpty();
    }

    // --- null subjects: one case per expectation whose subject can be absent --------------------------

    @Test
    void nullSubjectFailsHasSize() {
        List<Reaction> none = null;
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> ReactionsAssert.assertThat(none).hasSize(1)), "reactions", "size", 1, null);
    }

    @Test
    void nullSubjectFailsIsEmpty() {
        List<Reaction> none = null;
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> ReactionsAssert.assertThat(none).isEmpty()), "reactions", "size", 0, null);
    }

    @Test
    void nullSubjectFailsHasNoAborted() {
        List<Reaction> none = null;
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> ReactionsAssert.assertThat(none).hasNoAborted()),
                "reactions", "aborted reaction", "none", null);
    }

    @Test
    void nullSubjectFailsHasNoAbortedRecursively() {
        List<Reaction> none = null;
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> ReactionsAssert.assertThat(none).hasNoAbortedRecursively()),
                "reactions", "aborted reaction", "none", null);
    }

    @Test
    void nullSubjectFailsForEntity() {
        List<Reaction> none = null;
        Entity entity = new Entity("e", State.READY);
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> ReactionsAssert.assertThat(none).forEntity(TestDomain.KEY, entity)),
                "reactions", "entity reactions", entity, null);
    }

    // --- null arguments stay programming errors -------------------------------------------------------

    @Test
    void aNullRequiredArgumentIsAnArgumentErrorEvenWhenTheSubjectIsNull() {
        List<Reaction> none = null;
        Entity entity = new Entity("e", State.READY);
        assertEquals("key", assertThrows(NullPointerException.class,
                () -> ReactionsAssert.assertThat(none).forEntity(null, entity)).getMessage());
        assertEquals("entity", assertThrows(NullPointerException.class,
                () -> ReactionsAssert.assertThat(none).forEntity(TestDomain.KEY, null)).getMessage());
    }

    @Test
    void aNullExpectedCauseIsAnArgumentError() {
        Entity entity = new Entity("e", State.READY);
        AbortedReactionAssert<Entity, State> view = single(
                new Reaction.Aborted(TestDomain.KEY, entity, new IllegalStateException("down")), entity)
                .isAborted();
        assertEquals("expected",
                assertThrows(NullPointerException.class, () -> view.hasCause(null)).getMessage());
        assertEquals("expected",
                assertThrows(NullPointerException.class, () -> view.hasCauseSameAs(null)).getMessage());
    }

    @Test
    void aNullArgumentOfTheLocalizedCastIsAnArgumentError() {
        Entity entity = new Entity("e", State.READY);
        assertEquals("key", assertThrows(NullPointerException.class,
                () -> CheckedTypes.outcome(null, applied(entity))).getMessage());
        assertEquals("outcome", assertThrows(NullPointerException.class,
                () -> CheckedTypes.outcome(TestDomain.KEY, null)).getMessage());
    }

    // --- against the real engine ----------------------------------------------------------------------

    /**
     * The watch fires, the owner's action throws, and the abort is reported on the commit that triggered it
     * instead of propagating: the parcel keeps the state it committed and the shipment keeps the one it had.
     */
    @Test
    void aReactionThatAbortsIsReportedOnTheCommitThatTriggeredIt() {
        Shipment shipment = new Shipment("s1", ShipmentStatus.OPEN);
        Parcel parcel = new Parcel("p1", ParcelStatus.LOADING, Optional.of(shipment));
        sendShipment = (TransitionContext<Shipment, ShipmentStatus> ctx) -> {
            throw new IllegalStateException("the carrier is down");
        };

        try (MachineRegistry registry = registry()) {
            Outcome<Parcel, ParcelStatus> outcome =
                    registry.of(PARCELS).transitionTo(parcel, ParcelStatus.LOADED);
            ReactionsAssert reactions = OutcomeAssert.assertThat(outcome).isApplied()
                    .movedTo(ParcelStatus.LOADED).reactions();
            reactions.hasSize(1);
            assertThrows(GraphAssertionError.class, reactions::hasNoAborted);
            reactions.forEntity(SHIPMENTS, shipment).single().isAborted()
                    .hasCause(TransitionAbortedException.class);
            assertEquals(ParcelStatus.LOADED, parcel.status());
            assertEquals(ShipmentStatus.OPEN, shipment.status());
        }
    }

    /** The owner cannot even be read, so the abort is attributed to the peer that moved, not to the owner. */
    @Test
    void anOwnerThatCannotBeReadKeepsThePeersKey() {
        Shipment shipment = new Shipment("s1", null);
        Parcel parcel = new Parcel("p1", ParcelStatus.LOADING, Optional.of(shipment));

        try (MachineRegistry registry = registry()) {
            Outcome<Parcel, ParcelStatus> outcome =
                    registry.of(PARCELS).transitionTo(parcel, ParcelStatus.LOADED);
            ReactionsAssert reactions = OutcomeAssert.assertThat(outcome).isApplied().reactions();
            reactions.hasSize(1);
            reactions.forEntity(SHIPMENTS, shipment).hasSize(0);
            reactions.forEntity(PARCELS, parcel).single().isAborted()
                    .hasCause(UninitializedStateException.class);
        }
    }

    /** The watch runs and no rule matches, which is a completed re-evaluation carrying an ignored outcome. */
    @Test
    void aWatchThatMatchesNoRuleIsADoneReactionWithAnIgnoredOutcome() {
        Shipment shipment = new Shipment("s1", ShipmentStatus.OPEN);
        Parcel first = new Parcel("p1", ParcelStatus.LOADING, Optional.of(shipment));
        new Parcel("p2", ParcelStatus.LOADING, Optional.of(shipment));

        try (MachineRegistry registry = registry()) {
            Outcome<Parcel, ParcelStatus> outcome =
                    registry.of(PARCELS).transitionTo(first, ParcelStatus.LOADED);
            ReactionsAssert reactions = OutcomeAssert.assertThat(outcome).isApplied().reactions();
            reactions.hasNoAbortedRecursively();
            reactions.forEntity(SHIPMENTS, shipment).single().isDone().outcome()
                    .isIgnored().hasRecordedState(ShipmentStatus.OPEN).hasEntitySameAs(shipment);
            assertEquals(ShipmentStatus.OPEN, shipment.status());
        }
    }

    /** The rule matches, so the reaction carries the owner's own applied outcome and its hops. */
    @Test
    void aWatchThatMatchesCarriesTheOwnersAppliedOutcome() {
        Shipment shipment = new Shipment("s1", ShipmentStatus.OPEN);
        Parcel parcel = new Parcel("p1", ParcelStatus.LOADING, Optional.of(shipment));

        try (MachineRegistry registry = registry()) {
            Outcome<Parcel, ParcelStatus> outcome =
                    registry.of(PARCELS).transitionTo(parcel, ParcelStatus.LOADED);
            ReactionsAssert reactions = OutcomeAssert.assertThat(outcome).isApplied().reactions();
            reactions.hasNoAborted().hasNoAbortedRecursively();
            reactions.forEntity(SHIPMENTS, shipment).single().isDone().outcome()
                    .isApplied().movedTo(ShipmentStatus.SENT)
                    .records().forEntity(SHIPMENTS, shipment).single()
                    .from(ShipmentStatus.OPEN).to(ShipmentStatus.SENT).hasActionNamed("SendShipment");
            assertEquals(ShipmentStatus.SENT, shipment.status());
        }
    }

    // --- fixtures --------------------------------------------------------------------------------------

    private static final MachineKey<Other, Flag> OTHERS = MachineKey.of(Other.class, Flag.class);
    private static final MachineKey<Entity, Shelf> SHELVES = MachineKey.of(Entity.class, Shelf.class);
    private static final MachineKey<Shipment, ShipmentStatus> SHIPMENTS =
            MachineKey.of(Shipment.class, ShipmentStatus.class);
    private static final MachineKey<Parcel, ParcelStatus> PARCELS =
            MachineKey.of(Parcel.class, ParcelStatus.class);

    /** States of {@link Other}, an enum that belongs to no machine the assertions here narrow to. */
    enum Flag { OFF, ON }

    /** Entity of a second machine, so a selection has something of another key to skip. */
    record Other(String id) { }

    /** A second state enum of {@link Entity}: one entity class belongs to as many machines as it has states. */
    enum Shelf { OUT, IN }

    /** States of {@link Shipment}: the machine that watches. */
    enum ShipmentStatus { OPEN, SENT }

    /** States of {@link Parcel}: the machine that is watched. */
    enum ParcelStatus { LOADING, LOADED }

    /** Owner entity of the engine cases; its parcels register themselves as they are built. */
    static final class Shipment {

        private final String id;
        private final List<Parcel> parcels = new ArrayList<>();
        private ShipmentStatus status;

        Shipment(String id, ShipmentStatus status) {
            this.id = id;
            this.status = status;
        }

        String id() { return id; }

        List<Parcel> parcels() { return parcels; }

        ShipmentStatus status() { return status; }

        void setStatus(ShipmentStatus status) { this.status = status; }
    }

    /** Peer entity of the engine cases: moving one re-evaluates its shipment. */
    static final class Parcel {

        private final String id;
        private final Optional<Shipment> shipment;
        private ParcelStatus status;

        Parcel(String id, ParcelStatus status, Optional<Shipment> shipment) {
            this.id = id;
            this.status = status;
            this.shipment = shipment;
            shipment.ifPresent((Shipment owner) -> owner.parcels().add(this));
        }

        String id() { return id; }

        Optional<Shipment> shipment() { return shipment; }

        ParcelStatus status() { return status; }

        void setStatus(ParcelStatus status) { this.status = status; }
    }

    private TransitionAction<Shipment, ShipmentStatus> sendShipment =
            (TransitionContext<Shipment, ShipmentStatus> ctx) -> ActionOutcome.ok();

    private MachineRegistry registry() {
        GraphDefinition<Shipment, ShipmentStatus> shipments = StateGraph.define(SHIPMENTS)
                .initial(ShipmentStatus.OPEN).terminal(ShipmentStatus.SENT)
                .from(ShipmentStatus.OPEN)
                    .to(ShipmentStatus.SENT).action("SendShipment",
                            (TransitionContext<Shipment, ShipmentStatus> ctx) -> sendShipment.process(ctx))
                    .stay()
                    .watches(PARCELS, Shipment::parcels, Parcel::shipment)
                        .when(allIn(ParcelStatus.LOADED)).goTo(ShipmentStatus.SENT)
                    .forbidOthers("an open shipment only sends")
                .from(ShipmentStatus.SENT).stay().forbidOthers("sent is final")
                .build();
        GraphDefinition<Parcel, ParcelStatus> parcels = StateGraph.define(PARCELS)
                .initial(ParcelStatus.LOADING).terminal(ParcelStatus.LOADED)
                .from(ParcelStatus.LOADING).to(ParcelStatus.LOADED).move().stay()
                    .forbidOthers("a loading parcel only loads")
                .from(ParcelStatus.LOADED).stay().forbidOthers("loaded is final")
                .build();
        return MachineRegistry.builder()
                .add(StateMachines.from(shipments)
                        .state(Shipment::status, Shipment::setStatus).identity(Shipment::id))
                .add(StateMachines.from(parcels)
                        .state(Parcel::status, Parcel::setStatus).identity(Parcel::id))
                .build();
    }

    /** A cause whose every instance calls itself equal, so {@code equals} and {@code ==} disagree on it. */
    static final class EqualCause extends RuntimeException {

        EqualCause() {
            super("equal");
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof EqualCause;
        }

        @Override
        public int hashCode() {
            return 1;
        }
    }

    /**
     * {@code depth} nested outcomes where every level holds the SAME child outcome twice: linear to build
     * and to visit once, and a tree of 2^depth reactions to anything that visits a shared outcome twice.
     */
    private static Outcome<Entity, State> sharedChain(Entity entity, int depth) {
        Outcome<Entity, State> level = applied(entity);
        for (int step = 0; step < depth; step++) {
            Reaction.Done child = done(entity, level);
            level = new Outcome.Applied<>(entity, State.NEW, State.READY, List.of(), List.of(child, child));
        }
        return level;
    }

    private static Outcome.Applied<Entity, State> applied(Entity entity) {
        return new Outcome.Applied<>(entity, State.NEW, State.READY, List.of(), List.of());
    }

    private static Reaction.Done done(Entity entity, Outcome<?, ?> outcome) {
        return new Reaction.Done(TestDomain.KEY, entity, outcome);
    }

    private static ReactionAssert<Entity, State> single(Reaction reaction, Entity entity) {
        return ReactionsAssert.assertThat(List.of(reaction)).forEntity(TestDomain.KEY, entity).single();
    }

    private static void assertFailure(
            GraphAssertionError error, String context, String property, Object expected, Object actual) {
        assertEquals(context, error.context());
        assertEquals(property, error.property());
        assertEquals(expected, error.expected());
        assertEquals(actual, error.actual());
    }
}
