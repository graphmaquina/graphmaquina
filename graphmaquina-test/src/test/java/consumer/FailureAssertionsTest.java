package consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.ActionRecord;
import io.github.graphmaquina.api.ActionStatus;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.CascadePolicy;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineCall;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.PeerView;
import io.github.graphmaquina.api.Reaction;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.TransitionKind;
import io.github.graphmaquina.api.TransitionRecord;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.error.AbortDetails;
import io.github.graphmaquina.error.AfterCommitException;
import io.github.graphmaquina.error.CascadeException;
import io.github.graphmaquina.error.CommitFailedException;
import io.github.graphmaquina.error.InvalidGraphException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.error.UnhandledFailureException;
import io.github.graphmaquina.error.WatchChainExceededException;
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
import io.github.graphmaquina.test.fixtures.TestDomain.Id;
import io.github.graphmaquina.test.fixtures.TestDomain.State;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Contract of the failure views: which phase of the engine a throwable came from, what that phase carries,
 * and what the capture helper does with a body that never failed. A mismatch is read off the fields of the
 * {@link GraphAssertionError} rather than off its type alone, except in the few cases that only need a check to
 * fail; entities are pinned to identity rather than equality, and every public expectation whose subject can be
 * null gets its own null-subject case.
 */
class FailureAssertionsTest {

    // --- the premise of this task: the phases are not interchangeable -----------------------------

    @Test
    void afterCommitIsNotAnAbort() {
        Entity entity = new Entity("e", State.READY);
        IllegalStateException cause = new IllegalStateException("audit down");
        AfterCommitException error =
                afterCommit(TestDomain.KEY, entity, State.NEW, Id.PREPARE, State.READY, "audit", cause);
        TransitionFailureAssert.assertThat(error).isAfterCommit()
                .hasHookNamed("audit").hasCauseSameAs(cause);
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
        CommitFailedException commit = new CommitFailedException(CALL, List.of(), List.of(), "nothing written");
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
        TransitionAbortedException abort = new TransitionAbortedException(CALL, CallPhase.ACTION, details(), "no");
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> TransitionFailureAssert.assertThat(abort).isCommitFailure()),
                "failure", "type", CommitFailedException.class, abort);
    }

    /**
     * Narrowing follows Java's own hierarchy instead of contradicting it: every abort subclass, the aggregate
     * included, is an abort, and only the aggregate is a cascade abort.
     */
    @Test
    void everyAbortSubclassIsAnAbortAndOnlyTheAggregateIsACascade() {
        TransitionAbortedException plain = new TransitionAbortedException(CALL, CallPhase.ACTION, details(), "no");
        UnhandledFailureException unhandled = new UnhandledFailureException(CALL, details(), "change of mind");
        CascadeException aggregate = new CascadeException(CALL, details(), List.of(plain));
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
                afterCommit(TestDomain.KEY, new Entity("e", State.READY), State.NEW, Id.PREPARE, State.READY,
                        "audit", cause)).isAfterCommit();
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
                new TransitionAbortedException(CALL, CallPhase.ACTION, details(), "no", cause)).isAbort();
        assertSame(view, view.hasCauseSameAs(cause));
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasCauseSameAs(twin)),
                "abort", "cause", twin, cause);
    }

    @Test
    void aFailureWithoutACauseSatisfiesOnlyHasNoCause() {
        AbortAssert without = TransitionFailureAssert.assertThat(
                new TransitionAbortedException(CALL, CallPhase.ACTION, details(), "no")).isAbort();
        assertSame(without, without.hasNoCause());
        assertFailure(assertThrows(GraphAssertionError.class, () -> without.hasCause(RuntimeException.class)),
                "abort", "cause", RuntimeException.class, null);
        IllegalStateException cause = new IllegalStateException("boom");
        assertFailure(assertThrows(GraphAssertionError.class, () -> without.hasCauseSameAs(cause)),
                "abort", "cause", cause, null);
        AbortAssert with = TransitionFailureAssert.assertThat(
                new TransitionAbortedException(CALL, CallPhase.ACTION, details(), "no", cause)).isAbort();
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
        AbortAssert view = TransitionFailureAssert.assertThat(new TransitionAbortedException(CALL, CallPhase.ACTION,
                new AbortDetails(OTHERS, entity, Flag.OFF, Optional.of(Switch.FLIP),
                        Optional.of(Flag.ON), Optional.of("flip"), List.of(), List.of()), "no")).isAbort();
        assertSame(view, view.at(OTHERS, entity, Flag.OFF, Switch.FLIP, Flag.ON));
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> view.at(TestDomain.KEY, new Entity("e", State.NEW), State.NEW, Id.PREPARE, State.READY)),
                "abort", "key", TestDomain.KEY, OTHERS);
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> view.at(OTHERS, twin, Flag.OFF, Switch.FLIP, Flag.ON)),
                "abort", "entity", twin, entity);
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> view.at(OTHERS, entity, Flag.ON, Switch.FLIP, Flag.ON)),
                "abort", "from", Flag.ON, Flag.OFF);
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> view.at(OTHERS, entity, Flag.OFF, Switch.TOGGLE, Flag.ON)),
                "abort", "transition", Optional.of(Switch.TOGGLE), Optional.of(Switch.FLIP));
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> view.at(OTHERS, entity, Flag.OFF, Switch.FLIP, Flag.OFF)),
                "abort", "to", Optional.of(Flag.OFF), Optional.of(Flag.ON));
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> view.atUnresolved(OTHERS, entity, Flag.OFF, Switch.FLIP)),
                "abort", "to", Optional.empty(), Optional.of(Flag.ON));
    }

    /** An abort raised before an edge was resolved reports the identifier and no destination at all. */
    @Test
    void anAbortBeforeAnyEdgeWasResolvedReportsNoDestination() {
        Other entity = new Other("o");
        AbortAssert view = TransitionFailureAssert.assertThat(new TransitionAbortedException(CALL, CallPhase.ACTION,
                new AbortDetails(OTHERS, entity, Flag.OFF, Optional.of(Switch.FLIP), Optional.empty(),
                        Optional.empty(), List.of(), List.of()), "no edge")).isAbort();
        assertSame(view, view.atUnresolved(OTHERS, entity, Flag.OFF, Switch.FLIP));
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> view.atUnresolved(OTHERS, entity, Flag.OFF, Switch.TOGGLE)),
                "abort", "transition", Optional.of(Switch.TOGGLE), Optional.of(Switch.FLIP));
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> view.at(OTHERS, entity, Flag.OFF, Switch.FLIP, Flag.ON)),
                "abort", "to", Optional.of(Flag.ON), Optional.empty());
    }

    /**
     * An abort can be raised before any identifier was read, so that it names neither an identifier nor a
     * destination. {@code at} and {@code atUnresolved} both compare the identifier, which is why
     * {@code atUnread} exists: it locates such an abort by machine, entity and origin and requires the rest absent.
     */
    @Test
    void anAbortBeforeAnyIdentifierWasReadIsLocatedByMachineEntityAndOrigin() {
        Other entity = new Other("o");
        Other twin = new Other("o");
        AbortAssert view = TransitionFailureAssert.assertThat(new TransitionAbortedException(CALL, CallPhase.ACTION,
                new AbortDetails(OTHERS, entity, Flag.OFF, Optional.empty(), Optional.empty(), Optional.empty(),
                        List.of(), List.of()), "chain too long")).isAbort();
        assertSame(view, view.atUnread(OTHERS, entity, Flag.OFF));
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> view.atUnread(TestDomain.KEY, new Entity("e", State.NEW), State.NEW)),
                "abort", "key", TestDomain.KEY, OTHERS);
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.atUnread(OTHERS, twin, Flag.OFF)),
                "abort", "entity", twin, entity);
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.atUnread(OTHERS, entity, Flag.ON)),
                "abort", "from", Flag.ON, Flag.OFF);
    }

    @Test
    void anAbortThatReadAnIdentifierIsNotOneThatReadNone() {
        Other entity = new Other("o");
        AbortAssert resolved = TransitionFailureAssert.assertThat(new TransitionAbortedException(CALL, CallPhase.ACTION,
                new AbortDetails(OTHERS, entity, Flag.OFF, Optional.of(Switch.FLIP), Optional.of(Flag.ON),
                        Optional.empty(), List.of(), List.of()), "no")).isAbort();
        assertFailure(assertThrows(GraphAssertionError.class, () -> resolved.atUnread(OTHERS, entity, Flag.OFF)),
                "abort", "transition", Optional.empty(), Optional.of(Switch.FLIP));
        AbortAssert unresolved = TransitionFailureAssert.assertThat(
                new TransitionAbortedException(CALL, CallPhase.ACTION,
                        new AbortDetails(OTHERS, entity, Flag.OFF, Optional.of(Switch.FLIP), Optional.empty(),
                                Optional.empty(), List.of(), List.of()), "no edge")).isAbort();
        assertFailure(assertThrows(GraphAssertionError.class, () -> unresolved.atUnread(OTHERS, entity, Flag.OFF)),
                "abort", "transition", Optional.empty(), Optional.of(Switch.FLIP));
    }

    @Test
    void aNullRequiredArgumentOfAtUnreadIsAnArgumentError() {
        Other entity = new Other("o");
        AbortAssert view = TransitionFailureAssert.assertThat(new TransitionAbortedException(CALL, CallPhase.ACTION,
                new AbortDetails(OTHERS, entity, Flag.OFF, Optional.empty(), Optional.empty(), Optional.empty(),
                        List.of(), List.of()), "chain too long")).isAbort();
        assertThrows(NullPointerException.class, () -> view.atUnread(null, entity, Flag.OFF));
        assertThrows(NullPointerException.class, () -> view.atUnread(OTHERS, null, Flag.OFF));
        assertThrows(NullPointerException.class, () -> view.atUnread(OTHERS, entity, null));
    }

    /**
     * The engine aborts a chain of watch reactions that exceeds {@code maxWatchChain} before it reads any
     * identifier, in valid use, so this is the abort {@code atUnread} is for.
     */
    @Test
    void aWatchChainThatExceedsItsLimitAbortsBeforeAnyIdentifierWasRead() {
        try (MachineRegistry registry = pingPong(1)) {
            Doc doc = new Doc("d1", Phase.DRAFT);
            Outcome<Doc, Phase, Step> root = registry.of(DOCS).execute(doc, Step.SUBMIT);
            Reaction.Done first = (Reaction.Done) ((Outcome.Applied<?, ?, ?>) root).reactions().get(0);
            Outcome.Applied<?, ?, ?> nested = (Outcome.Applied<?, ?, ?>) first.outcome();
            RuntimeException cause = ((Reaction.Aborted) nested.reactions().get(0)).cause();
            assertInstanceOf(WatchChainExceededException.class, cause);
            AbortAssert view = TransitionFailureAssert.assertThat(cause).isAbort();
            assertSame(view, view.atUnread(DOCS, doc, Phase.DRAFT));
            view.hasNoAction().hasCascadePathExactly(List.of());
            for (Step step : Step.values()) {
                assertThrows(GraphAssertionError.class, () -> view.atUnresolved(DOCS, doc, Phase.DRAFT, step));
            }
        }
    }

    @Test
    void aKeyThatEqualsTheFailuresKeyMatchesEvenAsAFreshInstance() {
        Other entity = new Other("o");
        MachineKey<Other, Flag, Switch> sameValue = MachineKey.of(Other.class, Flag.class, Switch.class);
        assertEquals(OTHERS, sameValue);
        assertNotSame(OTHERS, sameValue);
        TransitionFailureAssert.assertThat(new TransitionAbortedException(CALL, CallPhase.ACTION,
                new AbortDetails(OTHERS, entity, Flag.OFF, Optional.of(Switch.FLIP), Optional.of(Flag.ON),
                        Optional.empty(), List.of(), List.of()),
                "no")).isAbort().at(sameValue, entity, Flag.OFF, Switch.FLIP, Flag.ON);
        TransitionFailureAssert.assertThat(
                afterCommit(OTHERS, entity, Flag.OFF, Switch.FLIP, Flag.ON, "audit",
                        new IllegalStateException("audit down"))).isAfterCommit()
                .at(sameValue, entity, Flag.OFF, Switch.FLIP, Flag.ON);
    }

    @Test
    void anAbortNamesTheActionThatWasRunningOrNoneAtAll() {
        AbortAssert named = TransitionFailureAssert.assertThat(
                new TransitionAbortedException(CALL, CallPhase.ACTION, details(), "no")).isAbort();
        assertSame(named, named.hasActionNamed("prepare"));
        assertFailure(assertThrows(GraphAssertionError.class, () -> named.hasActionNamed("audit")),
                "abort", "action", Optional.of("audit"), Optional.of("prepare"));
        assertFailure(assertThrows(GraphAssertionError.class, named::hasNoAction),
                "abort", "action", Optional.empty(), Optional.of("prepare"));
        AbortAssert anonymous = TransitionFailureAssert.assertThat(
                new TransitionAbortedException(CALL, CallPhase.ACTION,
                        new AbortDetails(TestDomain.KEY, new Entity("e", State.NEW), State.NEW,
                                Optional.of(Id.PREPARE), Optional.of(State.READY), Optional.empty(),
                                List.of(), List.of()), "no")).isAbort();
        assertSame(anonymous, anonymous.hasNoAction());
        assertFailure(assertThrows(GraphAssertionError.class, () -> anonymous.hasActionNamed("prepare")),
                "abort", "action", Optional.of("prepare"), Optional.empty());
    }

    @Test
    void theCascadePathIsComparedAsTheLiteralListTheEngineWrote() {
        AbortAssert view = TransitionFailureAssert.assertThat(new TransitionAbortedException(CALL, CallPhase.ACTION,
                new AbortDetails(TestDomain.KEY, new Entity("e", State.NEW), State.NEW,
                        Optional.of(Id.PREPARE), Optional.of(State.READY), Optional.empty(),
                        List.of("Parcel/ParcelStatus#p1", "Label/LabelStatus#l1"), List.of()), "no")).isAbort();
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
        ActionRecord ran = new ActionRecord(TestDomain.KEY, entity, State.NEW, Id.PREPARE, State.READY,
                "prepare", 0, ActionStatus.THREW);
        JournalAssert journal = TransitionFailureAssert.assertThat(
                new TransitionAbortedException(CALL, CallPhase.ACTION,
                        new AbortDetails(TestDomain.KEY, entity, State.NEW, Optional.of(Id.PREPARE),
                                Optional.of(State.READY), Optional.of("prepare"), List.of(), List.of(ran)),
                        "no")).isAbort().journal();
        journal.hasSize(1).hasActionsExactly("prepare").hasStatusesExactly(ActionStatus.THREW);
    }

    /**
     * A journal says which failure it was read from. The aggregate's own journal and the journal of one of
     * its collected elements are different lists, and a failure over either must not read the same.
     */
    @Test
    void aJournalNamesTheFailureItWasReadFrom() {
        Entity entity = new Entity("e", State.NEW);
        ActionRecord ran = new ActionRecord(TestDomain.KEY, entity, State.NEW, Id.PREPARE, State.READY,
                "prepare", 0, ActionStatus.THREW);
        AbortDetails withJournal = new AbortDetails(TestDomain.KEY, entity, State.NEW, Optional.of(Id.PREPARE),
                Optional.of(State.READY), Optional.of("prepare"), List.of(), List.of(ran));
        AbortAssert abort = TransitionFailureAssert.assertThat(
                new TransitionAbortedException(CALL, CallPhase.ACTION, withJournal, "no")).isAbort();
        assertFailure(assertThrows(GraphAssertionError.class, () -> abort.journal().hasSize(2)),
                "abort.journal", "size", 2, 1);
        CascadeAbortAssert cascade = TransitionFailureAssert.assertThat(
                new CascadeException(CALL, withJournal, List.of(elementAbort(new Other("a"))))).isCascadeAbort();
        assertFailure(assertThrows(GraphAssertionError.class, () -> cascade.journal().hasSize(2)),
                "cascade abort.journal", "size", 2, 1);
        assertFailure(assertThrows(GraphAssertionError.class, () -> cascade.error(0).journal().hasSize(2)),
                "cascade abort[0].journal", "size", 2, 0);
    }

    // --- the collected cascade ----------------------------------------------------------------------

    @Test
    void theCollectedErrorsAreReadOneAtATimeInOrder() {
        Other first = new Other("a");
        Other second = new Other("b");
        CascadeException aggregate = new CascadeException(CALL, details(),
                List.of(elementAbort(first), elementAbort(second)));
        CascadeAbortAssert view = TransitionFailureAssert.assertThat(aggregate).isCascadeAbort();
        assertSame(view, view.hasErrorCount(2));
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasErrorCount(1)),
                "cascade abort", "error count", 1, 2);
        view.error(0).at(OTHERS, first, Flag.OFF, Switch.FLIP, Flag.ON);
        view.error(1).at(OTHERS, second, Flag.OFF, Switch.FLIP, Flag.ON);
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> view.error(0).at(OTHERS, second, Flag.OFF, Switch.FLIP, Flag.ON)),
                "cascade abort[0]", "entity", second, first);
    }

    @Test
    void anIndexBeyondTheCollectedErrorsFailsAsAnAssertion() {
        CascadeAbortAssert view = TransitionFailureAssert.assertThat(
                new CascadeException(CALL, details(), List.of(elementAbort(new Other("a"))))).isCascadeAbort();
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.error(1)),
                "cascade abort", "index 1 present", true, false);
        assertThrows(IllegalArgumentException.class, () -> view.error(-1));
    }

    @Test
    void theAggregateHandsOverTheParentsJournalNotAnElements() {
        Entity entity = new Entity("e", State.NEW);
        ActionRecord ran = new ActionRecord(TestDomain.KEY, entity, State.NEW, Id.PREPARE, State.READY,
                "prepare", 0, ActionStatus.THREW);
        CascadeException aggregate = new CascadeException(CALL,
                new AbortDetails(TestDomain.KEY, entity, State.NEW, Optional.of(Id.PREPARE),
                        Optional.of(State.READY), Optional.of("prepare"), List.of(), List.of(ran)),
                List.of(elementAbort(new Other("a"))));
        TransitionFailureAssert.assertThat(aggregate).isCascadeAbort().journal()
                .hasSize(1).hasActionsExactly("prepare").hasStatusesExactly(ActionStatus.THREW);
    }

    // --- the commit --------------------------------------------------------------------------------

    @Test
    void theCommitListsAndTheFailedEntityAreComparedByIdentityAndOrder() {
        Other written = new Other("a");
        Other twin = new Other("a");
        Other failed = new Other("f");
        Other never = new Other("b");
        assertEquals(written, twin);
        assertNotSame(written, twin);
        CommitFailureAssert view = TransitionFailureAssert.assertThat(new CommitFailedException(CALL, List.of(),
                List.of(written), failed, List.of(never), new IllegalStateException("a setter threw")))
                .isCommitFailure();
        assertSame(view, view.hasAppliedExactly(written));
        assertSame(view, view.hasFailedEntity(failed));
        assertSame(view, view.hasPendingExactly(never));
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasAppliedExactly(twin)),
                "commit failure[0]", "applied", twin, written);
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasPendingExactly(twin)),
                "commit failure[0]", "pending", twin, never);
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasFailedEntity(never)),
                "commit failure", "failed entity", Optional.of(never), Optional.of(failed));
        assertFailure(assertThrows(GraphAssertionError.class, view::hasNoFailedEntity),
                "commit failure", "failed entity", Optional.empty(), Optional.of(failed));
        CommitFailureAssert none = TransitionFailureAssert.assertThat(
                new CommitFailedException(CALL, List.of(), List.of(never), "the state moved")).isCommitFailure();
        assertSame(none, none.hasNoFailedEntity());
        assertFailure(assertThrows(GraphAssertionError.class, () -> none.hasFailedEntity(failed)),
                "commit failure", "failed entity", Optional.of(failed), Optional.empty());
    }

    @Test
    void aCommitListOfTheWrongLengthOrOrderIsRejected() {
        Other first = new Other("a");
        Other second = new Other("b");
        CommitFailureAssert view = TransitionFailureAssert.assertThat(
                new CommitFailedException(CALL, List.of(), List.of(first, second), new Other("f"), List.of(),
                        new IllegalStateException("a setter threw"))).isCommitFailure();
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

    // --- the after-commit hook ---------------------------------------------------------------------

    @Test
    void theAfterCommitFailureNamesTheHookAndTheHopItWasRunningFor() {
        Other entity = new Other("o");
        Other twin = new Other("o");
        IllegalStateException cause = new IllegalStateException("audit down");
        AfterCommitAssert view = TransitionFailureAssert.assertThat(
                afterCommit(OTHERS, entity, Flag.OFF, Switch.FLIP, Flag.ON, "audit", cause)).isAfterCommit();
        assertSame(view, view.hasHookNamed("audit").at(OTHERS, entity, Flag.OFF, Switch.FLIP, Flag.ON));
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasHookNamed("persist")),
                "after-commit failure", "hook", "persist", "audit");
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> view.at(TestDomain.KEY, new Entity("e", State.NEW), State.NEW, Id.PREPARE, State.READY)),
                "after-commit failure", "key", TestDomain.KEY, OTHERS);
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> view.at(OTHERS, twin, Flag.OFF, Switch.FLIP, Flag.ON)),
                "after-commit failure", "entity", twin, entity);
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> view.at(OTHERS, entity, Flag.ON, Switch.FLIP, Flag.ON)),
                "after-commit failure", "from", Flag.ON, Flag.OFF);
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> view.at(OTHERS, entity, Flag.OFF, Switch.TOGGLE, Flag.ON)),
                "after-commit failure", "transition", Switch.TOGGLE, Switch.FLIP);
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> view.at(OTHERS, entity, Flag.OFF, Switch.FLIP, Flag.OFF)),
                "after-commit failure", "to", Flag.OFF, Flag.ON);
        view.records().hasSize(1);
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.records().hasSize(2)),
                "after-commit failure.records", "size", 2, 1);
    }

    // --- the DSL -----------------------------------------------------------------------------------

    @Test
    void anInvalidGraphIsReadByItsCodesAndTheirMultiplicity() {
        InvalidGraphException unreachable = assertThrows(InvalidGraphException.class,
                () -> StateGraph.define(LABELS).initial(LabelStatus.BLANK)
                        .terminal(LabelStatus.BLANK).terminal(LabelStatus.PRINTED).terminal(LabelStatus.VOID)
                        .build());
        InvalidGraphAssert view = InvalidGraphAssert.assertThat(unreachable);
        assertSame(view, view.hasCodesExactly("SG203", "SG203"));
        assertSame(view, view.hasProblemCount(2));
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasCodesExactly("SG203")),
                "invalid graph", "codes", List.of("SG203"), List.of("SG203", "SG203"));
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasProblemCount(1)),
                "invalid graph", "problem count", 1, 2);
    }

    /**
     * The route a consumer without JUnit has: {@code captureFailure} hands back a {@link Throwable}, which
     * binds to the failure view, so the DSL view is reached by narrowing rather than by a cast.
     */
    @Test
    void anInvalidGraphCapturedAsAThrowableIsReachedByNarrowing() {
        Throwable failure = TransitionFailureAssert.captureFailure(
                () -> StateGraph.define(LABELS).terminal(LabelStatus.BLANK)
                        .terminal(LabelStatus.PRINTED).terminal(LabelStatus.VOID).build());
        TransitionFailureAssert.assertThat(failure).isInvalidGraph().hasCodesExactly("SG201");
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> TransitionFailureAssert.assertThat(failure).isInvalidGraph().hasCodesExactly("SG203")),
                "invalid graph", "codes", List.of("SG203"), List.of("SG201"));
    }

    @Test
    void everyProblemIsReportedWithItsOwnCodeInOrder() {
        InvalidGraphException several = assertThrows(InvalidGraphException.class,
                () -> StateGraph.define(LABELS).initial(LabelStatus.BLANK)
                        .terminal(LabelStatus.PRINTED).terminal(LabelStatus.VOID).build());
        InvalidGraphAssert.assertThat(several)
                .hasCodesExactly("SG202", "SG203", "SG203", "SG701").hasProblemCount(4);
        assertFailure(assertThrows(GraphAssertionError.class, () -> InvalidGraphAssert.assertThat(several)
                        .hasCodesExactly("SG203", "SG202", "SG203", "SG701")),
                "invalid graph", "codes", List.of("SG203", "SG202", "SG203", "SG701"),
                List.of("SG202", "SG203", "SG203", "SG701"));
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> InvalidGraphAssert.assertThat(several).hasCodesExactly("SG202", "SG203", "SG701")),
                "invalid graph", "codes", List.of("SG202", "SG203", "SG701"),
                List.of("SG202", "SG203", "SG203", "SG701"));
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
                new TransitionAbortedException(CALL, CallPhase.ACTION, details(), "no")).isAbort();
        assertThrows(NullPointerException.class,
                () -> abort.at(null, entity, State.NEW, Id.PREPARE, State.READY));
        assertThrows(NullPointerException.class,
                () -> abort.at(TestDomain.KEY, null, State.NEW, Id.PREPARE, State.READY));
        assertThrows(NullPointerException.class,
                () -> abort.at(TestDomain.KEY, entity, null, Id.PREPARE, State.READY));
        assertThrows(NullPointerException.class,
                () -> abort.at(TestDomain.KEY, entity, State.NEW, null, State.READY));
        assertThrows(NullPointerException.class,
                () -> abort.at(TestDomain.KEY, entity, State.NEW, Id.PREPARE, null));
        assertThrows(NullPointerException.class, () -> abort.hasActionNamed(null));
        assertThrows(NullPointerException.class, () -> abort.hasCascadePathExactly(null));
        AfterCommitAssert after = TransitionFailureAssert.assertThat(
                afterCommit(TestDomain.KEY, entity, State.NEW, Id.PREPARE, State.READY, "audit",
                        new IllegalStateException("boom"))).isAfterCommit();
        assertThrows(NullPointerException.class, () -> after.hasHookNamed(null));
        assertThrows(NullPointerException.class, () -> after.at(null, entity, State.NEW, Id.PREPARE, State.READY));
        CommitFailureAssert commit = TransitionFailureAssert.assertThat(
                new CommitFailedException(CALL, List.of(), List.of(), "nothing")).isCommitFailure();
        assertThrows(NullPointerException.class, () -> commit.hasFailedEntity(null));
        assertThrows(NullPointerException.class, () -> commit.hasAppliedExactly((Object[]) null));
        assertThrows(NullPointerException.class, () -> commit.hasPendingExactly((Object[]) null));
    }

    // --- against the real engine -------------------------------------------------------------------

    @Test
    void theFailedEntityMayHaveBeenMutatedBeforeItsSetterThrew() {
        Entity entity = new Entity("e", State.NEW);
        IllegalStateException setterFailure = new IllegalStateException("setter wrote then failed");
        GraphDefinition<Entity, State, Id> graph = StateGraph.define(TestDomain.KEY).initial(State.NEW)
                .terminal(State.DONE).terminal(State.FAILED)
                .statePolicy(State.NEW, "x").statePolicy(State.READY, "x")
                .transition(Id.PREPARE, State.NEW, State.READY).endTransition()
                .transition(Id.RECOVER, State.NEW, State.FAILED).endTransition()
                .transition(Id.FINISH, State.READY, State.DONE).endTransition()
                .build();

        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(graph).state(Entity::state, (subject, state) -> {
                    subject.setState(state);
                    throw setterFailure;
                }).identity(Entity::id)).build()) {
            Throwable failure = TransitionFailureAssert.captureFailure(
                    () -> registry.of(TestDomain.KEY).execute(entity, Id.PREPARE));
            TransitionFailureAssert.assertThat(failure).isCommitFailure()
                    .hasAppliedExactly().hasFailedEntity(entity).hasPendingExactly().hasCauseSameAs(setterFailure);
            assertEquals(State.READY, entity.state());
        }
    }

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
        send = (TransitionContext<Parcel, ParcelStatus, Delivery> ctx) -> {
            ctx.cascade(LABELS, label, Printing.PRINT);
            return ActionOutcome.ok();
        };

        try (MachineRegistry registry = registry()) {
            Throwable failure = TransitionFailureAssert.captureFailure(
                    () -> registry.of(PARCELS).execute(parcel, Delivery.SEND));
            CommitFailureAssert view = TransitionFailureAssert.assertThat(failure).isCommitFailure();
            assertSame(view,
                    view.hasAppliedExactly(parcel).hasFailedEntity(label).hasPendingExactly().hasCauseSameAs(boom));
            assertEquals(ParcelStatus.SENT, parcel.status());
            assertEquals(LabelStatus.BLANK, label.status());
            assertThrows(GraphAssertionError.class, view::hasAppliedExactly);
        }
    }

    /**
     * A COLLECT cascade tries every label, so both refused ones are collected instead of the first aborting
     * the loop. The aggregate is an abort too, and its journal is the parent's: no edge leaves VOID under
     * PRINT, so each element aborts before resolving anything and reports no destination at all.
     */
    @Test
    void aCollectedCascadeAggregatesOneErrorPerForbiddenElement() {
        Parcel parcel = new Parcel("p1", ParcelStatus.HELD);
        Label first = new Label("l1", LabelStatus.VOID);
        Label second = new Label("l2", LabelStatus.VOID);
        send = (TransitionContext<Parcel, ParcelStatus, Delivery> ctx) -> {
            ctx.cascadeAll(LABELS, List.of(first, second), Printing.PRINT, CascadePolicy.COLLECT);
            return ActionOutcome.ok();
        };

        try (MachineRegistry registry = registry()) {
            Throwable failure = TransitionFailureAssert.captureFailure(
                    () -> registry.of(PARCELS).execute(parcel, Delivery.SEND));
            CascadeAbortAssert view = TransitionFailureAssert.assertThat(failure).isCascadeAbort();
            assertSame(view, view.hasErrorCount(2).hasNoCause());
            view.error(0).atUnresolved(LABELS, first, LabelStatus.VOID, Printing.PRINT).hasNoAction()
                    .hasCascadePathExactly(List.of("Parcel/ParcelStatus#p1"));
            view.error(1).atUnresolved(LABELS, second, LabelStatus.VOID, Printing.PRINT).hasNoAction();
            view.journal().hasActionsExactly("Send").hasStatusesExactly(ActionStatus.THREW);
            TransitionFailureAssert.assertThat(failure).isAbort()
                    .at(PARCELS, parcel, ParcelStatus.HELD, Delivery.SEND, ParcelStatus.SENT).hasNoAction();
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
        send = (TransitionContext<Parcel, ParcelStatus, Delivery> ctx) -> ActionOutcome.ok();
        audit = (Parcel sent) -> { throw boom; };

        try (MachineRegistry registry = registry()) {
            Throwable failure = TransitionFailureAssert.captureFailure(
                    () -> registry.of(PARCELS).execute(parcel, Delivery.SEND));
            TransitionFailureAssert.assertThat(failure).isAfterCommit()
                    .at(PARCELS, parcel, ParcelStatus.HELD, Delivery.SEND, ParcelStatus.SENT)
                    .hasHookNamed("audit").hasCauseSameAs(boom);
            assertEquals(ParcelStatus.SENT, parcel.status());
            assertThrows(GraphAssertionError.class,
                    () -> TransitionFailureAssert.assertThat(failure).isAbort());
        }
    }

    /** An action that fails without an onFailure route aborts: nothing moved, and the journal says so. */
    @Test
    void anActionThatFailsWithoutARouteAbortsAndLeavesEverythingWhereItWas() {
        Parcel parcel = new Parcel("p1", ParcelStatus.HELD);
        send = (TransitionContext<Parcel, ParcelStatus, Delivery> ctx) -> ActionOutcome.failed("change of mind");

        try (MachineRegistry registry = registry()) {
            Throwable failure = TransitionFailureAssert.captureFailure(
                    () -> registry.of(PARCELS).execute(parcel, Delivery.SEND));
            TransitionFailureAssert.assertThat(failure).isAbort()
                    .at(PARCELS, parcel, ParcelStatus.HELD, Delivery.SEND, ParcelStatus.SENT)
                    .hasActionNamed("Send").hasCascadePathExactly(List.of("Parcel/ParcelStatus#p1"))
                    .journal().hasActionsExactly("Send").hasStatusesExactly(ActionStatus.FAILED);
            assertEquals(ParcelStatus.HELD, parcel.status());
        }
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private static final MachineKey<Other, Flag, Switch> OTHERS =
            MachineKey.of(Other.class, Flag.class, Switch.class);
    private static final MachineKey<Parcel, ParcelStatus, Delivery> PARCELS =
            MachineKey.of(Parcel.class, ParcelStatus.class, Delivery.class);
    private static final MachineKey<Label, LabelStatus, Printing> LABELS =
            MachineKey.of(Label.class, LabelStatus.class, Printing.class);

    /** States of {@link Other}, a machine that exists only to be named where the subject's is expected. */
    enum Flag { OFF, ON }

    /** Transition identifiers of that machine; {@code TOGGLE} is never declared by an edge. */
    enum Switch { FLIP, TOGGLE }

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

    /** Transition identifiers of the parcel machine. */
    enum Delivery { SEND, LOSE }

    /** States of {@link Label}, the peer the parent cascades to. */
    enum LabelStatus { BLANK, PRINTED, VOID }

    /** Transition identifiers of the label machine. */
    enum Printing { PRINT, VOID }

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

    private TransitionAction<Parcel, ParcelStatus, Delivery> send =
            (TransitionContext<Parcel, ParcelStatus, Delivery> ctx) -> ActionOutcome.ok();
    private Consumer<Parcel> audit = (Parcel parcel) -> { };

    private MachineRegistry registry() {
        GraphDefinition<Parcel, ParcelStatus, Delivery> parcels = StateGraph.define(PARCELS)
                .initial(ParcelStatus.HELD).terminal(ParcelStatus.SENT).terminal(ParcelStatus.LOST)
                .statePolicy(ParcelStatus.HELD, "a held parcel is either sent or lost")
                .transition(Delivery.SEND, ParcelStatus.HELD, ParcelStatus.SENT)
                    .action("Send", (TransitionContext<Parcel, ParcelStatus, Delivery> ctx) -> send.process(ctx))
                    .afterCommit("audit", (Parcel parcel) -> audit.accept(parcel))
                    .endTransition()
                .transition(Delivery.LOSE, ParcelStatus.HELD, ParcelStatus.LOST).endTransition()
                .build();
        GraphDefinition<Label, LabelStatus, Printing> labels = StateGraph.define(LABELS)
                .initial(LabelStatus.BLANK).terminal(LabelStatus.PRINTED).terminal(LabelStatus.VOID)
                .statePolicy(LabelStatus.BLANK, "a blank label either prints or is voided")
                .transition(Printing.PRINT, LabelStatus.BLANK, LabelStatus.PRINTED).endTransition()
                .transition(Printing.VOID, LabelStatus.BLANK, LabelStatus.VOID).endTransition()
                .build();
        return MachineRegistry.builder()
                .add(StateMachines.from(parcels).state(Parcel::status, Parcel::setStatus).identity(Parcel::id))
                .add(StateMachines.from(labels).state(Label::status, Label::setStatus).identity(Label::id))
                .build();
    }

    private static final MachineKey<Doc, Phase, Step> DOCS = MachineKey.of(Doc.class, Phase.class, Step.class);

    /**
     * States of {@link Doc}; {@code DRAFT} and {@code REVIEW} each declare a watch that sends the document to
     * the other.
     */
    enum Phase { DRAFT, REVIEW, PUBLISHED, ARCHIVED }

    /** Transition identifiers of the document machine. */
    enum Step { SUBMIT, SEND_BACK, PUBLISH, ARCHIVE }

    /** Entity of the ping-pong machine. */
    static final class Doc {

        private final String id;
        private Phase phase;

        Doc(String id, Phase phase) {
            this.id = id;
            this.phase = phase;
        }

        String id() { return id; }

        Phase phase() { return phase; }

        void setPhase(Phase phase) { this.phase = phase; }
    }

    /**
     * A document in DRAFT or REVIEW watches itself and always executes the edge to the other one, so every
     * commit triggers a reaction that commits again, and {@code maxWatchChain} is the only thing that ends it.
     */
    private static MachineRegistry pingPong(int maxWatchChain) {
        GraphDefinition<Doc, Phase, Step> graph = StateGraph.define(DOCS)
                .initial(Phase.DRAFT).terminal(Phase.ARCHIVED).allowsCycle(Phase.REVIEW)
                .statePolicy(Phase.DRAFT, "x").statePolicy(Phase.REVIEW, "x").statePolicy(Phase.PUBLISHED, "x")
                .transition(Step.SUBMIT, Phase.DRAFT, Phase.REVIEW).endTransition()
                .transition(Step.SEND_BACK, Phase.REVIEW, Phase.DRAFT).endTransition()
                .transition(Step.PUBLISH, Phase.REVIEW, Phase.PUBLISHED).endTransition()
                .transition(Step.ARCHIVE, Phase.PUBLISHED, Phase.ARCHIVED).endTransition()
                .watch(Phase.DRAFT, DOCS, (Doc doc) -> List.of(doc), (Doc doc) -> Optional.of(doc))
                    .when("always", (Doc doc, Collection<? extends Doc> peers, PeerView<Doc, Phase, Step> view) -> true)
                        .execute(Step.SUBMIT)
                    .endWatch()
                .watch(Phase.REVIEW, DOCS, (Doc doc) -> List.of(doc), (Doc doc) -> Optional.of(doc))
                    .when("always", (Doc doc, Collection<? extends Doc> peers, PeerView<Doc, Phase, Step> view) -> true)
                        .execute(Step.SEND_BACK)
                    .endWatch()
                .build();
        return MachineRegistry.builder()
                .add(StateMachines.from(graph).state(Doc::phase, Doc::setPhase).identity(Doc::id))
                .maxWatchChain(maxWatchChain)
                .build();
    }

    private static final MachineCall CALL =
            new MachineCall(TestDomain.KEY, new Entity("e", State.NEW), Optional.of(Id.PREPARE), false);

    /** The failure of a hook on the committed hop {@code from -transition-> to}, the only hop of its call. */
    private static <E, S extends Enum<S>, T extends Enum<T>> AfterCommitException afterCommit(
            MachineKey<E, S, T> key, E entity, S from, T transition, S to, String hook, Throwable cause) {
        TransitionRecord<E, S, T> hop = new TransitionRecord<>(key, entity, from, transition, to, TransitionKind.MOVE,
                Optional.empty(), 0);
        return new AfterCommitException(CALL, List.of(), List.of(hop), hop, hook, cause);
    }

    private static AbortDetails details() {
        return new AbortDetails(TestDomain.KEY, new Entity("e", State.NEW), State.NEW,
                Optional.of(Id.PREPARE), Optional.of(State.READY), Optional.of("prepare"),
                List.of(), List.of());
    }

    private static TransitionAbortedException elementAbort(Other entity) {
        return new TransitionAbortedException(CALL, CallPhase.ACTION, new AbortDetails(OTHERS, entity, Flag.OFF,
                Optional.of(Switch.FLIP), Optional.of(Flag.ON), Optional.empty(), List.of(), List.of()),
                "rejected inside a cascade");
    }

    private static void assertFailure(
            GraphAssertionError error, String context, String property, Object expected, Object actual) {
        assertEquals(context, error.context());
        assertEquals(property, error.property());
        assertEquals(expected, error.expected());
        assertEquals(actual, error.actual());
    }
}
