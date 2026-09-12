package consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.ActionRecord;
import io.github.graphmaquina.api.ActionStatus;
import io.github.graphmaquina.api.CascadePolicy;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.error.AbortDetails;
import io.github.graphmaquina.error.AfterCommitException;
import io.github.graphmaquina.error.CascadeException;
import io.github.graphmaquina.error.CommitFailedException;
import io.github.graphmaquina.error.InvalidGraphException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.error.UnhandledFailureException;
import io.github.graphmaquina.runtime.MachineRegistry;
import io.github.graphmaquina.runtime.StateMachines;
import io.github.graphmaquina.test.AbortAssert;
import io.github.graphmaquina.test.AfterCommitAssert;
import io.github.graphmaquina.test.CascadeAbortAssert;
import io.github.graphmaquina.test.CommitFailureAssert;
import io.github.graphmaquina.test.GraphAssertionError;
import io.github.graphmaquina.test.InvalidGraphAssert;
import io.github.graphmaquina.test.JournalAssert;
import io.github.graphmaquina.test.TransitionFailureAssert;
import io.github.graphmaquina.test.fixtures.TestDomain;
import io.github.graphmaquina.test.fixtures.TestDomain.Entity;
import io.github.graphmaquina.test.fixtures.TestDomain.State;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Contract of the failure views: which phase of the engine a throwable came from, what that phase carries,
 * and what the capture helper does with a body that never failed. Every mismatch is read off the fields of
 * the {@link GraphAssertionError} rather than off its type alone, entities are pinned to identity rather
 * than equality, and every public expectation whose subject can be null gets its own null-subject case.
 */
class FailureAssertionsTest {

    // --- the premise of this task: the phases are not interchangeable -----------------------------

    @Test
    void afterCommitIsNotAnAbort() {
        Entity entity = new Entity("e", State.READY);
        IllegalStateException cause = new IllegalStateException("audit down");
        AfterCommitException error =
                new AfterCommitException(TestDomain.KEY, entity, State.NEW, State.READY, "audit", cause);
        TransitionFailureAssert.assertThat(error).isAfterCommit()
                .hasStepNamed("audit").hasCauseSameAs(cause);
        assertThrows(GraphAssertionError.class,
                () -> TransitionFailureAssert.assertThat(error).isAbort());
    }

    @Test
    void missingFailureCannotBeCapturedAsTheHelpersOwnError() {
        assertThrows(GraphAssertionError.class,
                () -> TransitionFailureAssert.captureFailure(() -> { }));
        AssertionError assertion = new AssertionError("from test");
        assertSame(assertion, assertThrows(AssertionError.class,
                () -> TransitionFailureAssert.captureFailure(() -> { throw assertion; })));
    }

    // --- capturing ---------------------------------------------------------------------------------

    @Test
    void captureFailureReturnsTheExceptionWithoutAssertingAnythingAboutIt() {
        IllegalStateException boom = new IllegalStateException("boom");
        assertSame(boom, TransitionFailureAssert.captureFailure(() -> { throw boom; }));
    }

    @Test
    void captureFailureRestoresTheInterruptFlagItSwallowed() {
        assertFalse(Thread.currentThread().isInterrupted());
        InterruptedException stopped = new InterruptedException("stopped");
        assertSame(stopped, TransitionFailureAssert.captureFailure(() -> { throw stopped; }));
        assertTrue(Thread.interrupted());
    }

    @Test
    void aBodyThatCompletedNormallyIsReportedWithItsOwnFields() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> TransitionFailureAssert.captureFailure(() -> { }));
        assertFailure(error, "operation", "failure", "an exception", "completed normally");
    }

    @Test
    void aNullOperationIsAnArgumentError() {
        assertThrows(NullPointerException.class, () -> TransitionFailureAssert.captureFailure(null));
    }

    // --- narrowing ---------------------------------------------------------------------------------

    @Test
    void eachNarrowingNamesTheTypeItWanted() {
        CommitFailedException commit = new CommitFailedException(List.of(), List.of(), "nothing written");
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> TransitionFailureAssert.assertThat(commit).isAbort()),
                "failure", "type", TransitionAbortedException.class, commit);
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> TransitionFailureAssert.assertThat(commit).isCascadeAbort()),
                "failure", "type", CascadeException.class, commit);
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> TransitionFailureAssert.assertThat(commit).isAfterCommit()),
                "failure", "type", AfterCommitException.class, commit);
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> TransitionFailureAssert.assertThat(commit).isInvalidGraph()),
                "failure", "type", InvalidGraphException.class, commit);
        TransitionAbortedException abort = new TransitionAbortedException(details(), "no");
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> TransitionFailureAssert.assertThat(abort).isCommitFailure()),
                "failure", "type", CommitFailedException.class, abort);
    }

    /** The ruling of this task: narrowing follows Java's own hierarchy instead of contradicting it. */
    @Test
    void everyAbortSubclassIsAnAbortAndOnlyTheAggregateIsACascade() {
        TransitionAbortedException plain = new TransitionAbortedException(details(), "no");
        UnhandledFailureException unhandled = new UnhandledFailureException(details(), "change of mind");
        CascadeException aggregate = new CascadeException(details(), List.of(plain));
        TransitionFailureAssert.assertThat(plain).isAbort().hasActionNamed("prepare");
        TransitionFailureAssert.assertThat(unhandled).isAbort().hasActionNamed("prepare");
        TransitionFailureAssert.assertThat(aggregate).isAbort().hasActionNamed("prepare");
        TransitionFailureAssert.assertThat(aggregate).isCascadeAbort().hasErrorCount(1);
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> TransitionFailureAssert.assertThat(unhandled).isCascadeAbort()),
                "failure", "type", CascadeException.class, unhandled);
    }

    // --- the cause, shared by every failure view ----------------------------------------------------

    @Test
    void theCauseIsMatchedByTypeSubclassesIncluded() {
        IllegalStateException cause = new IllegalStateException("audit down");
        AfterCommitAssert view = TransitionFailureAssert.assertThat(
                new AfterCommitException(TestDomain.KEY, new Entity("e", State.READY),
                        State.NEW, State.READY, "audit", cause)).isAfterCommit();
        assertSame(view, view.hasCause(IllegalStateException.class));
        assertSame(view, view.hasCause(RuntimeException.class));
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasCause(NumberFormatException.class)),
                "after-commit failure", "cause", NumberFormatException.class, cause);
    }

    @Test
    void theCauseIsMatchedByReferenceNotByEquality() {
        EqualCause cause = new EqualCause("boom");
        EqualCause twin = new EqualCause("boom");
        assertEquals(cause, twin);
        assertNotSame(cause, twin);
        AbortAssert view = TransitionFailureAssert.assertThat(
                new TransitionAbortedException(details(), "no", cause)).isAbort();
        assertSame(view, view.hasCauseSameAs(cause));
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasCauseSameAs(twin)),
                "abort", "cause", twin, cause);
    }

    @Test
    void aFailureWithoutACauseSatisfiesOnlyHasNoCause() {
        AbortAssert without = TransitionFailureAssert.assertThat(
                new TransitionAbortedException(details(), "no")).isAbort();
        assertSame(without, without.hasNoCause());
        assertFailure(assertThrows(GraphAssertionError.class, () -> without.hasCause(RuntimeException.class)),
                "abort", "cause", RuntimeException.class, null);
        IllegalStateException cause = new IllegalStateException("boom");
        assertFailure(assertThrows(GraphAssertionError.class, () -> without.hasCauseSameAs(cause)),
                "abort", "cause", cause, null);
        AbortAssert with = TransitionFailureAssert.assertThat(
                new TransitionAbortedException(details(), "no", cause)).isAbort();
        assertFailure(assertThrows(GraphAssertionError.class, with::hasNoCause),
                "abort", "cause", "no cause", cause);
    }

    // --- the abort ---------------------------------------------------------------------------------

    @Test
    void atComparesEveryFieldAndSelectsTheEntityByReference() {
        Other entity = new Other("o");
        Other twin = new Other("o");
        assertEquals(entity, twin);
        assertNotSame(entity, twin);
        AbortAssert view = TransitionFailureAssert.assertThat(new TransitionAbortedException(
                new AbortDetails(OTHERS, entity, Flag.OFF, Flag.ON, Optional.of("flip"),
                        List.of(), List.of()), "no")).isAbort();
        assertSame(view, view.at(OTHERS, entity, Flag.OFF, Flag.ON));
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> view.at(TestDomain.KEY, new Entity("e", State.NEW), State.NEW, State.READY)),
                "abort", "key", TestDomain.KEY, OTHERS);
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.at(OTHERS, twin, Flag.OFF, Flag.ON)),
                "abort", "entity", twin, entity);
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.at(OTHERS, entity, Flag.ON, Flag.ON)),
                "abort", "from", Flag.ON, Flag.OFF);
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.at(OTHERS, entity, Flag.OFF, Flag.OFF)),
                "abort", "to", Flag.OFF, Flag.ON);
    }

    @Test
    void aKeyThatEqualsTheFailuresKeyMatchesEvenAsAFreshInstance() {
        Other entity = new Other("o");
        MachineKey<Other, Flag> sameValue = MachineKey.of(Other.class, Flag.class);
        assertEquals(OTHERS, sameValue);
        assertNotSame(OTHERS, sameValue);
        TransitionFailureAssert.assertThat(new TransitionAbortedException(
                new AbortDetails(OTHERS, entity, Flag.OFF, Flag.ON, Optional.empty(), List.of(), List.of()),
                "no")).isAbort().at(sameValue, entity, Flag.OFF, Flag.ON);
        TransitionFailureAssert.assertThat(
                new AfterCommitException(OTHERS, entity, Flag.OFF, Flag.ON, "audit",
                        new IllegalStateException("audit down"))).isAfterCommit()
                .at(sameValue, entity, Flag.OFF, Flag.ON);
    }

    @Test
    void anAbortNamesTheActionThatWasRunningOrNoneAtAll() {
        AbortAssert named = TransitionFailureAssert.assertThat(
                new TransitionAbortedException(details(), "no")).isAbort();
        assertSame(named, named.hasActionNamed("prepare"));
        assertFailure(assertThrows(GraphAssertionError.class, () -> named.hasActionNamed("audit")),
                "abort", "action", Optional.of("audit"), Optional.of("prepare"));
        assertFailure(assertThrows(GraphAssertionError.class, named::hasNoAction),
                "abort", "action", Optional.empty(), Optional.of("prepare"));
        AbortAssert anonymous = TransitionFailureAssert.assertThat(new TransitionAbortedException(
                new AbortDetails(TestDomain.KEY, new Entity("e", State.NEW), State.NEW, State.READY,
                        Optional.empty(), List.of(), List.of()), "no")).isAbort();
        assertSame(anonymous, anonymous.hasNoAction());
        assertFailure(assertThrows(GraphAssertionError.class, () -> anonymous.hasActionNamed("prepare")),
                "abort", "action", Optional.of("prepare"), Optional.empty());
    }

    @Test
    void theCascadePathIsComparedAsTheLiteralListTheEngineWrote() {
        AbortAssert view = TransitionFailureAssert.assertThat(new TransitionAbortedException(
                new AbortDetails(TestDomain.KEY, new Entity("e", State.NEW), State.NEW, State.READY,
                        Optional.empty(), List.of("Parcel/ParcelStatus#p1", "Label/LabelStatus#l1"),
                        List.of()), "no")).isAbort();
        assertSame(view, view.hasCascadePathExactly(List.of("Parcel/ParcelStatus#p1", "Label/LabelStatus#l1")));
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> view.hasCascadePathExactly(List.of("Label/LabelStatus#l1", "Parcel/ParcelStatus#p1"))),
                "abort", "cascade path", List.of("Label/LabelStatus#l1", "Parcel/ParcelStatus#p1"),
                List.of("Parcel/ParcelStatus#p1", "Label/LabelStatus#l1"));
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> view.hasCascadePathExactly(List.of("Parcel/ParcelStatus#p1"))),
                "abort", "cascade path", List.of("Parcel/ParcelStatus#p1"),
                List.of("Parcel/ParcelStatus#p1", "Label/LabelStatus#l1"));
    }

    @Test
    void anAbortHandsItsJournalToTheJournalView() {
        Entity entity = new Entity("e", State.NEW);
        ActionRecord ran = new ActionRecord(TestDomain.KEY, entity, State.NEW, State.READY,
                "prepare", 0, ActionStatus.THREW);
        JournalAssert journal = TransitionFailureAssert.assertThat(new TransitionAbortedException(
                new AbortDetails(TestDomain.KEY, entity, State.NEW, State.READY, Optional.of("prepare"),
                        List.of(), List.of(ran)), "no")).isAbort().journal();
        journal.hasSize(1).hasActionsExactly("prepare").hasStatusesExactly(ActionStatus.THREW);
    }

    // --- the collected cascade ----------------------------------------------------------------------

    @Test
    void theCollectedErrorsAreReadOneAtATimeInOrder() {
        Other first = new Other("a");
        Other second = new Other("b");
        CascadeException aggregate = new CascadeException(details(),
                List.of(elementAbort(first), elementAbort(second)));
        CascadeAbortAssert view = TransitionFailureAssert.assertThat(aggregate).isCascadeAbort();
        assertSame(view, view.hasErrorCount(2));
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasErrorCount(1)),
                "cascade abort", "error count", 1, 2);
        view.error(0).at(OTHERS, first, Flag.OFF, Flag.ON);
        view.error(1).at(OTHERS, second, Flag.OFF, Flag.ON);
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.error(0).at(OTHERS, second, Flag.OFF, Flag.ON)),
                "cascade abort[0]", "entity", second, first);
    }

    @Test
    void anIndexBeyondTheCollectedErrorsFailsAsAnAssertion() {
        CascadeAbortAssert view = TransitionFailureAssert.assertThat(
                new CascadeException(details(), List.of(elementAbort(new Other("a"))))).isCascadeAbort();
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.error(1)),
                "cascade abort", "index 1 present", true, false);
        assertThrows(IllegalArgumentException.class, () -> view.error(-1));
    }

    @Test
    void theAggregateHandsOverTheParentsJournalNotAnElements() {
        Entity entity = new Entity("e", State.NEW);
        ActionRecord ran = new ActionRecord(TestDomain.KEY, entity, State.NEW, State.READY,
                "prepare", 0, ActionStatus.THREW);
        CascadeException aggregate = new CascadeException(
                new AbortDetails(TestDomain.KEY, entity, State.NEW, State.READY, Optional.of("prepare"),
                        List.of(), List.of(ran)),
                List.of(elementAbort(new Other("a"))));
        TransitionFailureAssert.assertThat(aggregate).isCascadeAbort().journal()
                .hasSize(1).hasActionsExactly("prepare").hasStatusesExactly(ActionStatus.THREW);
    }

    // --- the commit --------------------------------------------------------------------------------

    @Test
    void theTwoCommitListsAreComparedByIdentityAndOrder() {
        Other written = new Other("a");
        Other twin = new Other("a");
        Other never = new Other("b");
        assertEquals(written, twin);
        assertNotSame(written, twin);
        CommitFailureAssert view = TransitionFailureAssert.assertThat(
                new CommitFailedException(List.of(written), List.of(never), "a setter threw")).isCommitFailure();
        assertSame(view, view.hasAppliedExactly(written));
        assertSame(view, view.hasPendingExactly(never));
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasAppliedExactly(twin)),
                "commit failure[0]", "applied", twin, written);
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasPendingExactly(twin)),
                "commit failure[0]", "pending", twin, never);
    }

    @Test
    void aCommitListOfTheWrongLengthOrOrderIsRejected() {
        Other first = new Other("a");
        Other second = new Other("b");
        CommitFailureAssert view = TransitionFailureAssert.assertThat(
                new CommitFailedException(List.of(first, second), List.of(), "a setter threw")).isCommitFailure();
        assertSame(view, view.hasPendingExactly());
        assertFailure(assertThrows(GraphAssertionError.class, view::hasAppliedExactly),
                "commit failure", "applied size", 0, 2);
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasAppliedExactly(first)),
                "commit failure", "applied size", 1, 2);
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasAppliedExactly(second, first)),
                "commit failure[0]", "applied", second, first);
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasPendingExactly(first)),
                "commit failure", "pending size", 1, 0);
    }

    // --- the post-commit step ----------------------------------------------------------------------

    @Test
    void theAfterCommitStepNamesTheHookAndTheHopItWasRunningFor() {
        Other entity = new Other("o");
        Other twin = new Other("o");
        IllegalStateException cause = new IllegalStateException("audit down");
        AfterCommitAssert view = TransitionFailureAssert.assertThat(
                new AfterCommitException(OTHERS, entity, Flag.OFF, Flag.ON, "audit", cause)).isAfterCommit();
        assertSame(view, view.hasStepNamed("audit").at(OTHERS, entity, Flag.OFF, Flag.ON));
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasStepNamed("listener")),
                "after-commit failure", "step", "listener", "audit");
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> view.at(TestDomain.KEY, new Entity("e", State.NEW), State.NEW, State.READY)),
                "after-commit failure", "key", TestDomain.KEY, OTHERS);
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.at(OTHERS, twin, Flag.OFF, Flag.ON)),
                "after-commit failure", "entity", twin, entity);
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.at(OTHERS, entity, Flag.ON, Flag.ON)),
                "after-commit failure", "from", Flag.ON, Flag.OFF);
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.at(OTHERS, entity, Flag.OFF, Flag.OFF)),
                "after-commit failure", "to", Flag.OFF, Flag.ON);
    }

    // --- the DSL -----------------------------------------------------------------------------------

    @Test
    void anInvalidGraphIsReadByItsCodesAndTheirMultiplicity() {
        InvalidGraphException missingCells = assertThrows(InvalidGraphException.class,
                () -> StateGraph.define(LABELS).initial(LabelStatus.BLANK).build());
        InvalidGraphAssert view = InvalidGraphAssert.assertThat(missingCells);
        assertSame(view, view.hasCodesExactly("SG101"));
        assertSame(view, view.hasProblemCount(1));
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasCodesExactly("SG201")),
                "invalid graph", "codes", List.of("SG201"), List.of("SG101"));
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasProblemCount(2)),
                "invalid graph", "problem count", 2, 1);
    }

    /**
     * The route a consumer without JUnit has: {@code captureFailure} hands back a {@link Throwable}, which
     * binds to the failure view, so the DSL view is reached by narrowing rather than by a cast.
     */
    @Test
    void anInvalidGraphCapturedAsAThrowableIsReachedByNarrowing() {
        Throwable failure = TransitionFailureAssert.captureFailure(
                () -> StateGraph.define(LABELS).initial(LabelStatus.BLANK).build());
        TransitionFailureAssert.assertThat(failure).isInvalidGraph().hasCodesExactly("SG101");
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> TransitionFailureAssert.assertThat(failure).isInvalidGraph().hasCodesExactly("SG201")),
                "invalid graph", "codes", List.of("SG201"), List.of("SG101"));
    }

    @Test
    void everyProblemIsReportedWithItsOwnCodeInOrder() {
        InvalidGraphException both = assertThrows(InvalidGraphException.class,
                () -> StateGraph.define(LABELS).build());
        InvalidGraphAssert.assertThat(both).hasCodesExactly("SG101", "SG201").hasProblemCount(2);
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> InvalidGraphAssert.assertThat(both).hasCodesExactly("SG201", "SG101")),
                "invalid graph", "codes", List.of("SG201", "SG101"), List.of("SG101", "SG201"));
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> InvalidGraphAssert.assertThat(both).hasCodesExactly("SG101")),
                "invalid graph", "codes", List.of("SG101"), List.of("SG101", "SG201"));
    }

    // --- null subjects: one case per expectation whose subject can be absent ------------------------

    @Test
    void nullSubjectFailsIsAbort() {
        assertFailure(assertThrows(GraphAssertionError.class, () -> TransitionFailureAssert.assertThat(null).isAbort()),
                "failure", "type", TransitionAbortedException.class, null);
    }

    @Test
    void nullSubjectFailsIsCascadeAbort() {
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> TransitionFailureAssert.assertThat(null).isCascadeAbort()),
                "failure", "type", CascadeException.class, null);
    }

    @Test
    void nullSubjectFailsIsCommitFailure() {
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> TransitionFailureAssert.assertThat(null).isCommitFailure()),
                "failure", "type", CommitFailedException.class, null);
    }

    @Test
    void nullSubjectFailsIsAfterCommit() {
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> TransitionFailureAssert.assertThat(null).isAfterCommit()),
                "failure", "type", AfterCommitException.class, null);
    }

    @Test
    void nullSubjectFailsIsInvalidGraph() {
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> TransitionFailureAssert.assertThat(null).isInvalidGraph()),
                "failure", "type", InvalidGraphException.class, null);
    }

    @Test
    void nullSubjectFailsHasCause() {
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> TransitionFailureAssert.assertThat(null).hasCause(RuntimeException.class)),
                "failure", "cause", RuntimeException.class, null);
    }

    @Test
    void nullSubjectFailsHasCauseSameAs() {
        IllegalStateException cause = new IllegalStateException("boom");
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> TransitionFailureAssert.assertThat(null).hasCauseSameAs(cause)),
                "failure", "cause", cause, null);
    }

    @Test
    void nullSubjectFailsHasNoCause() {
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> TransitionFailureAssert.assertThat(null).hasNoCause()),
                "failure", "cause", "no cause", null);
    }

    @Test
    void nullSubjectFailsHasCodesExactly() {
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> InvalidGraphAssert.assertThat(null).hasCodesExactly("SG101")),
                "invalid graph", "codes", List.of("SG101"), null);
    }

    @Test
    void nullSubjectFailsHasProblemCount() {
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> InvalidGraphAssert.assertThat(null).hasProblemCount(1)),
                "invalid graph", "problem count", 1, null);
    }

    // --- null arguments stay programming errors ----------------------------------------------------

    @Test
    void aNullRequiredArgumentIsAnArgumentErrorEvenWhenTheSubjectIsNull() {
        assertThrows(NullPointerException.class, () -> TransitionFailureAssert.assertThat(null).hasCause(null));
        assertThrows(NullPointerException.class, () -> TransitionFailureAssert.assertThat(null).hasCauseSameAs(null));
        assertThrows(NullPointerException.class,
                () -> InvalidGraphAssert.assertThat(null).hasCodesExactly((String[]) null));
    }

    @Test
    void aNullRequiredArgumentOfANarrowedViewIsAnArgumentError() {
        Entity entity = new Entity("e", State.NEW);
        AbortAssert abort = TransitionFailureAssert.assertThat(
                new TransitionAbortedException(details(), "no")).isAbort();
        assertThrows(NullPointerException.class, () -> abort.at(null, entity, State.NEW, State.READY));
        assertThrows(NullPointerException.class, () -> abort.at(TestDomain.KEY, null, State.NEW, State.READY));
        assertThrows(NullPointerException.class, () -> abort.at(TestDomain.KEY, entity, null, State.READY));
        assertThrows(NullPointerException.class, () -> abort.at(TestDomain.KEY, entity, State.NEW, null));
        assertThrows(NullPointerException.class, () -> abort.hasActionNamed(null));
        assertThrows(NullPointerException.class, () -> abort.hasCascadePathExactly(null));
        AfterCommitAssert after = TransitionFailureAssert.assertThat(
                new AfterCommitException(TestDomain.KEY, entity, State.NEW, State.READY, "audit",
                        new IllegalStateException("boom"))).isAfterCommit();
        assertThrows(NullPointerException.class, () -> after.hasStepNamed(null));
        assertThrows(NullPointerException.class, () -> after.at(null, entity, State.NEW, State.READY));
        CommitFailureAssert commit = TransitionFailureAssert.assertThat(
                new CommitFailedException(List.of(), List.of(), "nothing")).isCommitFailure();
        assertThrows(NullPointerException.class, () -> commit.hasAppliedExactly((Object[]) null));
        assertThrows(NullPointerException.class, () -> commit.hasPendingExactly((Object[]) null));
    }

    // --- against the real engine -------------------------------------------------------------------

    /**
     * Phase 2 writes the parcel and then throws on the label's setter. Nothing is undone, so the parcel is
     * already sent while the label never moved: the view reports exactly that split, and the expectation a
     * clean rollback would satisfy fails on the very same exception.
     */
    @Test
    void aSetterThatThrowsLeavesTheEarlierWriteApplied() {
        Parcel parcel = new Parcel("p1", ParcelStatus.HELD);
        Label label = new Label("l1", LabelStatus.BLANK);
        IllegalStateException boom = new IllegalStateException("the label store is down");
        label.failOnWrite(boom);
        send = (TransitionContext<Parcel, ParcelStatus> ctx) -> {
            ctx.cascade(LABELS, label, LabelStatus.PRINTED);
            return ActionOutcome.ok();
        };

        try (MachineRegistry registry = registry()) {
            Throwable failure = TransitionFailureAssert.captureFailure(
                    () -> registry.of(PARCELS).transitionTo(parcel, ParcelStatus.SENT));
            CommitFailureAssert view = TransitionFailureAssert.assertThat(failure).isCommitFailure();
            assertSame(view, view.hasAppliedExactly(parcel).hasPendingExactly(label).hasCauseSameAs(boom));
            assertEquals(ParcelStatus.SENT, parcel.status());
            assertEquals(LabelStatus.BLANK, label.status());
            assertThrows(GraphAssertionError.class, view::hasAppliedExactly);
        }
    }

    /**
     * A COLLECT cascade tries every label, so both forbidden ones are collected instead of the first
     * aborting the loop. The aggregate is an abort too, and its journal is the parent's: the cascaded labels
     * moved through FORBID cells, which run no action at all.
     */
    @Test
    void aCollectedCascadeAggregatesOneErrorPerForbiddenElement() {
        Parcel parcel = new Parcel("p1", ParcelStatus.HELD);
        Label first = new Label("l1", LabelStatus.VOID);
        Label second = new Label("l2", LabelStatus.VOID);
        send = (TransitionContext<Parcel, ParcelStatus> ctx) -> {
            ctx.cascadeAll(LABELS, List.of(first, second), LabelStatus.PRINTED, CascadePolicy.COLLECT);
            return ActionOutcome.ok();
        };

        try (MachineRegistry registry = registry()) {
            Throwable failure = TransitionFailureAssert.captureFailure(
                    () -> registry.of(PARCELS).transitionTo(parcel, ParcelStatus.SENT));
            CascadeAbortAssert view = TransitionFailureAssert.assertThat(failure).isCascadeAbort();
            assertSame(view, view.hasErrorCount(2).hasNoCause());
            view.error(0).at(LABELS, first, LabelStatus.VOID, LabelStatus.PRINTED).hasNoAction()
                    .hasCascadePathExactly(List.of("Parcel/ParcelStatus#p1"));
            view.error(1).at(LABELS, second, LabelStatus.VOID, LabelStatus.PRINTED).hasNoAction();
            view.journal().hasActionsExactly("Send").hasStatusesExactly(ActionStatus.THREW);
            TransitionFailureAssert.assertThat(failure).isAbort()
                    .at(PARCELS, parcel, ParcelStatus.HELD, ParcelStatus.SENT).hasNoAction();
            assertEquals(ParcelStatus.HELD, parcel.status());
            assertEquals(LabelStatus.VOID, first.status());
            assertEquals(LabelStatus.VOID, second.status());
        }
    }

    /**
     * The hook runs once the state is already written, so the entity has moved even though the call threw.
     * That is what keeps this failure apart from an abort, which leaves the entity exactly where it was.
     */
    @Test
    void aHookThatThrowsArrivesWithTheEntityAlreadyMoved() {
        Parcel parcel = new Parcel("p1", ParcelStatus.HELD);
        IllegalStateException boom = new IllegalStateException("audit down");
        send = (TransitionContext<Parcel, ParcelStatus> ctx) -> ActionOutcome.ok();
        audit = (Parcel sent) -> { throw boom; };

        try (MachineRegistry registry = registry()) {
            Throwable failure = TransitionFailureAssert.captureFailure(
                    () -> registry.of(PARCELS).transitionTo(parcel, ParcelStatus.SENT));
            TransitionFailureAssert.assertThat(failure).isAfterCommit()
                    .at(PARCELS, parcel, ParcelStatus.HELD, ParcelStatus.SENT)
                    .hasStepNamed("audit").hasCauseSameAs(boom);
            assertEquals(ParcelStatus.SENT, parcel.status());
            assertThrows(GraphAssertionError.class,
                    () -> TransitionFailureAssert.assertThat(failure).isAbort());
        }
    }

    /** An action that fails without an onFailure route aborts: nothing moved, and the journal says so. */
    @Test
    void anActionThatFailsWithoutARouteAbortsAndLeavesEverythingWhereItWas() {
        Parcel parcel = new Parcel("p1", ParcelStatus.HELD);
        send = (TransitionContext<Parcel, ParcelStatus> ctx) -> ActionOutcome.failed("change of mind");

        try (MachineRegistry registry = registry()) {
            Throwable failure = TransitionFailureAssert.captureFailure(
                    () -> registry.of(PARCELS).transitionTo(parcel, ParcelStatus.SENT));
            TransitionFailureAssert.assertThat(failure).isAbort()
                    .at(PARCELS, parcel, ParcelStatus.HELD, ParcelStatus.SENT)
                    .hasActionNamed("Send").hasCascadePathExactly(List.of("Parcel/ParcelStatus#p1"))
                    .journal().hasActionsExactly("Send").hasStatusesExactly(ActionStatus.FAILED);
            assertEquals(ParcelStatus.HELD, parcel.status());
        }
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private static final MachineKey<Other, Flag> OTHERS = MachineKey.of(Other.class, Flag.class);
    private static final MachineKey<Parcel, ParcelStatus> PARCELS =
            MachineKey.of(Parcel.class, ParcelStatus.class);
    private static final MachineKey<Label, LabelStatus> LABELS = MachineKey.of(Label.class, LabelStatus.class);

    /** States of {@link Other}, a machine that exists only to be named where the subject's is expected. */
    enum Flag { OFF, ON }

    /** Entity of that machine: a record, so two instances can be equal and still not be the same one. */
    record Other(String id) { }

    /** A cause that compares equal to a twin, so an identity expectation cannot be met by equality. */
    static final class EqualCause extends RuntimeException {

        EqualCause(String message) {
            super(message);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof EqualCause that && String.valueOf(getMessage()).equals(that.getMessage());
        }

        @Override
        public int hashCode() {
            return String.valueOf(getMessage()).hashCode();
        }
    }

    /** States of {@link Parcel}, the parent of the engine cases. */
    enum ParcelStatus { HELD, SENT, LOST }

    /** States of {@link Label}, the peer the parent cascades to. */
    enum LabelStatus { BLANK, PRINTED, VOID }

    /** Parent entity of the engine cases, written by the machine and read back by the assertions. */
    static final class Parcel {

        private final String id;
        private ParcelStatus status;

        Parcel(String id, ParcelStatus status) {
            this.id = id;
            this.status = status;
        }

        String id() { return id; }

        ParcelStatus status() { return status; }

        void setStatus(ParcelStatus status) { this.status = status; }
    }

    /** Peer entity of the engine cases; its setter can be instrumented to fail phase 2. */
    static final class Label {

        private final String id;
        private LabelStatus status;
        private RuntimeException onWrite;

        Label(String id, LabelStatus status) {
            this.id = id;
            this.status = status;
        }

        String id() { return id; }

        LabelStatus status() { return status; }

        void failOnWrite(RuntimeException failure) { this.onWrite = failure; }

        void setStatus(LabelStatus status) {
            if (onWrite != null) {
                throw onWrite;
            }
            this.status = status;
        }
    }

    private TransitionAction<Parcel, ParcelStatus> send =
            (TransitionContext<Parcel, ParcelStatus> ctx) -> ActionOutcome.ok();
    private Consumer<Parcel> audit = (Parcel parcel) -> { };

    private MachineRegistry registry() {
        GraphDefinition<Parcel, ParcelStatus> parcels = StateGraph.define(PARCELS)
                .initial(ParcelStatus.HELD).terminal(ParcelStatus.SENT).terminal(ParcelStatus.LOST)
                .from(ParcelStatus.HELD)
                    .to(ParcelStatus.SENT)
                        .action("Send", (TransitionContext<Parcel, ParcelStatus> ctx) -> send.process(ctx))
                        .afterCommit("audit", (Parcel parcel) -> audit.accept(parcel))
                    .to(ParcelStatus.LOST).move()
                    .stay().forbidOthers("a held parcel is either sent or lost")
                .from(ParcelStatus.SENT).stay().forbidOthers("sent is final")
                .from(ParcelStatus.LOST).stay().forbidOthers("lost is final")
                .build();
        GraphDefinition<Label, LabelStatus> labels = StateGraph.define(LABELS)
                .initial(LabelStatus.BLANK).terminal(LabelStatus.PRINTED).terminal(LabelStatus.VOID)
                .from(LabelStatus.BLANK)
                    .to(LabelStatus.PRINTED).move()
                    .to(LabelStatus.VOID).move()
                    .stay().forbidOthers("a blank label either prints or is voided")
                .from(LabelStatus.PRINTED).stay().forbidOthers("printed is final")
                .from(LabelStatus.VOID).stay().forbidOthers("void is final")
                .build();
        return MachineRegistry.builder()
                .add(StateMachines.from(parcels).state(Parcel::status, Parcel::setStatus).identity(Parcel::id))
                .add(StateMachines.from(labels).state(Label::status, Label::setStatus).identity(Label::id))
                .build();
    }

    private static AbortDetails details() {
        return new AbortDetails(TestDomain.KEY, new Entity("e", State.NEW), State.NEW, State.READY,
                Optional.of("prepare"), List.of(), List.of());
    }

    private static TransitionAbortedException elementAbort(Other entity) {
        return new TransitionAbortedException(new AbortDetails(OTHERS, entity, Flag.OFF, Flag.ON,
                Optional.empty(), List.of(), List.of()), "rejected inside a cascade");
    }

    private static void assertFailure(
            GraphAssertionError error, String context, String property, Object expected, Object actual) {
        assertEquals(context, error.context());
        assertEquals(property, error.property());
        assertEquals(expected, error.expected());
        assertEquals(actual, error.actual());
    }
}
