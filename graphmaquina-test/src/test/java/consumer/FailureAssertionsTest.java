package consumer;

import static consumer.GraphAssertionErrors.assertFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionRecord;
import io.github.graphmaquina.api.ActionStatus;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.MachineCall;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.StatesWritten;
import io.github.graphmaquina.api.TransitionKind;
import io.github.graphmaquina.api.TransitionRecord;
import io.github.graphmaquina.error.AbortDetails;
import io.github.graphmaquina.error.AfterCommitException;
import io.github.graphmaquina.error.CallFailedException;
import io.github.graphmaquina.error.CascadeException;
import io.github.graphmaquina.error.CommitFailedException;
import io.github.graphmaquina.error.InvalidGraphException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.error.UnhandledFailureException;
import io.github.graphmaquina.test.AbortAssert;
import io.github.graphmaquina.test.AfterCommitAssert;
import io.github.graphmaquina.test.CallFailureAssert;
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
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Contract of the failure views: which phase of the engine a throwable came from, what that phase carries,
 * and what the capture helper does with a body that never failed. A mismatch is read off the fields of the
 * {@link GraphAssertionError} rather than off its type alone, except in the few cases that only need a check to
 * fail; entities are pinned to identity rather than equality, and every public expectation whose subject can be
 * null gets its own null-subject case. The failures that a real engine and a real graph definition raise are in
 * {@link EngineFailureAssertionsTest}.
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
                new AbortDetails(OTHERS, entity, Optional.of(Flag.OFF), Optional.of(Switch.FLIP),
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
                "abort", "from", Optional.of(Flag.ON), Optional.of(Flag.OFF));
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
                new AbortDetails(OTHERS, entity, Optional.of(Flag.OFF), Optional.of(Switch.FLIP), Optional.empty(),
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
                new AbortDetails(OTHERS, entity, Optional.of(Flag.OFF), Optional.empty(), Optional.empty(),
                        Optional.empty(), List.of(), List.of()), "chain too long")).isAbort();
        assertSame(view, view.atUnread(OTHERS, entity, Flag.OFF));
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> view.atUnread(TestDomain.KEY, new Entity("e", State.NEW), State.NEW)),
                "abort", "key", TestDomain.KEY, OTHERS);
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.atUnread(OTHERS, twin, Flag.OFF)),
                "abort", "entity", twin, entity);
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.atUnread(OTHERS, entity, Flag.ON)),
                "abort", "from", Optional.of(Flag.ON), Optional.of(Flag.OFF));
    }

    @Test
    void anAbortBeforeTheStateWasReadIsLocatedWithoutAState() {
        Entity entity = new Entity("e", State.NEW);
        AbortAssert executed = TransitionFailureAssert.assertThat(new TransitionAbortedException(CALL,
                CallPhase.RESOLVE, new AbortDetails(TestDomain.KEY, entity, Optional.empty(), Optional.of(Id.PREPARE),
                        Optional.empty(), Optional.empty(), List.of(), List.of()), "reading the entity threw"))
                .isAbort();
        assertSame(executed, executed.atUnreadState(TestDomain.KEY, entity, Id.PREPARE));
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> executed.atUnreadState(TestDomain.KEY, entity, Id.FINISH)),
                "abort", "transition", Optional.of(Id.FINISH), Optional.of(Id.PREPARE));
        assertFailure(assertThrows(GraphAssertionError.class, () -> executed.atUnreadState(TestDomain.KEY, entity)),
                "abort", "transition", Optional.empty(), Optional.of(Id.PREPARE));
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> executed.atUnresolved(TestDomain.KEY, entity, State.NEW, Id.PREPARE)),
                "abort", "from", Optional.of(State.NEW), Optional.empty());
        AbortAssert resolved = TransitionFailureAssert.assertThat(new TransitionAbortedException(CALL, CallPhase.ACTION,
                new AbortDetails(TestDomain.KEY, entity, Optional.of(State.NEW), Optional.of(Id.PREPARE),
                        Optional.of(State.READY), Optional.empty(), List.of(), List.of()), "no")).isAbort();
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> resolved.atUnreadState(TestDomain.KEY, entity, Id.PREPARE)),
                "abort", "from", Optional.empty(), Optional.of(State.NEW));
        Entity twin = new Entity("e", State.NEW);
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> executed.atUnreadState(OTHERS, new Other("o"), Switch.FLIP)),
                "abort", "key", OTHERS, TestDomain.KEY);
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> executed.atUnreadState(TestDomain.KEY, twin, Id.PREPARE)),
                "abort", "entity", twin, entity);
        AbortAssert withDestination = TransitionFailureAssert.assertThat(new TransitionAbortedException(CALL,
                CallPhase.RESOLVE, new AbortDetails(TestDomain.KEY, entity, Optional.empty(), Optional.of(Id.PREPARE),
                        Optional.of(State.READY), Optional.empty(), List.of(), List.of()), "no")).isAbort();
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> withDestination.atUnreadState(TestDomain.KEY, entity, Id.PREPARE)),
                "abort", "to", Optional.empty(), Optional.of(State.READY));
        assertThrows(NullPointerException.class, () -> executed.atUnreadState(null, entity, Id.PREPARE));
        assertThrows(NullPointerException.class, () -> executed.atUnreadState(TestDomain.KEY, null, Id.PREPARE));
        assertThrows(NullPointerException.class, () -> executed.atUnreadState(TestDomain.KEY, entity, null));
    }

    /** A reevaluation that could not read its entity's state reports neither a state nor an identifier. */
    @Test
    void aReevaluationAbortBeforeTheStateWasReadIsLocatedWithoutAnIdentifier() {
        Entity entity = new Entity("e", State.NEW);
        AbortAssert reevaluated = TransitionFailureAssert.assertThat(new TransitionAbortedException(
                new MachineCall(TestDomain.KEY, entity, Optional.empty(), false), CallPhase.RESOLVE,
                new AbortDetails(TestDomain.KEY, entity, Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), List.of(), List.of()), "reading the entity threw")).isAbort();
        assertSame(reevaluated, reevaluated.atUnreadState(TestDomain.KEY, entity));
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> reevaluated.atUnreadState(TestDomain.KEY, entity, Id.PREPARE)),
                "abort", "transition", Optional.of(Id.PREPARE), Optional.empty());
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> reevaluated.atUnread(TestDomain.KEY, entity, State.NEW)),
                "abort", "from", Optional.of(State.NEW), Optional.empty());
        Entity twin = new Entity("e", State.NEW);
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> reevaluated.atUnreadState(OTHERS, new Other("o"))),
                "abort", "key", OTHERS, TestDomain.KEY);
        assertFailure(assertThrows(GraphAssertionError.class, () -> reevaluated.atUnreadState(TestDomain.KEY, twin)),
                "abort", "entity", twin, entity);
        AbortAssert withState = TransitionFailureAssert.assertThat(new TransitionAbortedException(
                new MachineCall(TestDomain.KEY, entity, Optional.empty(), false), CallPhase.RESOLVE,
                new AbortDetails(TestDomain.KEY, entity, Optional.of(State.NEW), Optional.empty(), Optional.empty(),
                        Optional.empty(), List.of(), List.of()), "no")).isAbort();
        assertFailure(assertThrows(GraphAssertionError.class, () -> withState.atUnreadState(TestDomain.KEY, entity)),
                "abort", "from", Optional.empty(), Optional.of(State.NEW));
        AbortAssert withDestination = TransitionFailureAssert.assertThat(new TransitionAbortedException(
                new MachineCall(TestDomain.KEY, entity, Optional.empty(), false), CallPhase.RESOLVE,
                new AbortDetails(TestDomain.KEY, entity, Optional.empty(), Optional.empty(),
                        Optional.of(State.READY), Optional.empty(), List.of(), List.of()), "no")).isAbort();
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> withDestination.atUnreadState(TestDomain.KEY, entity)),
                "abort", "to", Optional.empty(), Optional.of(State.READY));
        assertThrows(NullPointerException.class, () -> reevaluated.atUnreadState(null, entity));
        assertThrows(NullPointerException.class, () -> reevaluated.atUnreadState(TestDomain.KEY, null));
    }

    @Test
    void anAbortThatReadAnIdentifierIsNotOneThatReadNone() {
        Other entity = new Other("o");
        AbortAssert resolved = TransitionFailureAssert.assertThat(new TransitionAbortedException(CALL, CallPhase.ACTION,
                new AbortDetails(OTHERS, entity, Optional.of(Flag.OFF), Optional.of(Switch.FLIP), Optional.of(Flag.ON),
                        Optional.empty(), List.of(), List.of()), "no")).isAbort();
        assertFailure(assertThrows(GraphAssertionError.class, () -> resolved.atUnread(OTHERS, entity, Flag.OFF)),
                "abort", "transition", Optional.empty(), Optional.of(Switch.FLIP));
        AbortAssert unresolved = TransitionFailureAssert.assertThat(
                new TransitionAbortedException(CALL, CallPhase.ACTION,
                        new AbortDetails(OTHERS, entity, Optional.of(Flag.OFF), Optional.of(Switch.FLIP),
                                Optional.empty(), Optional.empty(), List.of(), List.of()), "no edge")).isAbort();
        assertFailure(assertThrows(GraphAssertionError.class, () -> unresolved.atUnread(OTHERS, entity, Flag.OFF)),
                "abort", "transition", Optional.empty(), Optional.of(Switch.FLIP));
    }

    @Test
    void aNullRequiredArgumentOfAtUnreadIsAnArgumentError() {
        Other entity = new Other("o");
        AbortAssert view = TransitionFailureAssert.assertThat(new TransitionAbortedException(CALL, CallPhase.ACTION,
                new AbortDetails(OTHERS, entity, Optional.of(Flag.OFF), Optional.empty(), Optional.empty(),
                        Optional.empty(), List.of(), List.of()), "chain too long")).isAbort();
        assertThrows(NullPointerException.class, () -> view.atUnread(null, entity, Flag.OFF));
        assertThrows(NullPointerException.class, () -> view.atUnread(OTHERS, null, Flag.OFF));
        assertThrows(NullPointerException.class, () -> view.atUnread(OTHERS, entity, null));
    }

    @Test
    void aKeyThatEqualsTheFailuresKeyMatchesEvenAsAFreshInstance() {
        Other entity = new Other("o");
        MachineKey<Other, Flag, Switch> sameValue = MachineKey.of(Other.class, Flag.class, Switch.class);
        assertEquals(OTHERS, sameValue);
        assertNotSame(OTHERS, sameValue);
        TransitionFailureAssert.assertThat(new TransitionAbortedException(CALL, CallPhase.ACTION,
                new AbortDetails(OTHERS, entity, Optional.of(Flag.OFF), Optional.of(Switch.FLIP), Optional.of(Flag.ON),
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
                        new AbortDetails(TestDomain.KEY, new Entity("e", State.NEW), Optional.of(State.NEW),
                                Optional.of(Id.PREPARE), Optional.of(State.READY), Optional.empty(),
                                List.of(), List.of()), "no")).isAbort();
        assertSame(anonymous, anonymous.hasNoAction());
        assertFailure(assertThrows(GraphAssertionError.class, () -> anonymous.hasActionNamed("prepare")),
                "abort", "action", Optional.of("prepare"), Optional.empty());
    }

    @Test
    void theCascadePathIsComparedAsTheLiteralListTheEngineWrote() {
        AbortAssert view = TransitionFailureAssert.assertThat(new TransitionAbortedException(CALL, CallPhase.ACTION,
                new AbortDetails(TestDomain.KEY, new Entity("e", State.NEW), Optional.of(State.NEW),
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
                        new AbortDetails(TestDomain.KEY, entity, Optional.of(State.NEW), Optional.of(Id.PREPARE),
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
        AbortDetails withJournal = new AbortDetails(TestDomain.KEY, entity, Optional.of(State.NEW),
                Optional.of(Id.PREPARE), Optional.of(State.READY), Optional.of("prepare"), List.of(), List.of(ran));
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

    // --- what every failure of a call shares -------------------------------------------------------

    @Test
    void anyCallFailureIsReadByItsPhaseItsStatesWrittenItsCallAndItsJournal() {
        Entity entity = new Entity("e", State.NEW);
        ActionRecord ran = new ActionRecord(TestDomain.KEY, entity, State.NEW, Id.PREPARE, State.READY, "prepare", 0,
                ActionStatus.OK);
        CommitFailedException commit = new CommitFailedException(CALL, List.of(ran), List.of(entity),
                "the state moved");
        CallFailureAssert view = TransitionFailureAssert.assertThat(commit).isCallFailure();
        assertSame(view, view.hasPhase(CallPhase.COMMIT).hasStatesWritten(StatesWritten.NONE).hasCallSameAs(CALL));
        view.journal().hasActionsExactly("prepare").hasStatusesExactly(ActionStatus.OK);
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasPhase(CallPhase.HOOK)),
                "call failure", "phase", CallPhase.HOOK, CallPhase.COMMIT);
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasStatesWritten(StatesWritten.PARTIAL)),
                "call failure", "states written", StatesWritten.PARTIAL, StatesWritten.NONE);
        MachineCall other = new MachineCall(TestDomain.KEY, entity, Optional.of(Id.PREPARE), false);
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasCallSameAs(other)),
                "call failure", "call", other, CALL);
        assertThrows(NullPointerException.class, () -> view.hasPhase(null));
        assertThrows(NullPointerException.class, () -> view.hasStatesWritten(null));
        assertThrows(NullPointerException.class, () -> view.hasCallSameAs(null));
    }

    @Test
    void everyNarrowedViewOfACallFailureSharesThoseExpectations() {
        TransitionFailureAssert.assertThat(new TransitionAbortedException(CALL, CallPhase.GUARD, details(), "no"))
                .isAbort().hasPhase(CallPhase.GUARD).hasStatesWritten(StatesWritten.NONE).hasCallSameAs(CALL);
        TransitionFailureAssert.assertThat(new CascadeException(CALL, details(), List.of(elementAbort(new Other("a")))))
                .isCascadeAbort().hasPhase(CallPhase.ACTION).hasStatesWritten(StatesWritten.NONE);
        CommitFailedException commitFailed = new CommitFailedException(CALL, List.of(), List.of(), new Other("f"),
                List.of(), new IllegalStateException("a setter threw"));
        TransitionFailureAssert.assertThat(commitFailed)
                .isCommitFailure().hasPhase(CallPhase.COMMIT).hasStatesWritten(StatesWritten.PARTIAL);
        TransitionFailureAssert.assertThat(afterCommit(TestDomain.KEY, new Entity("e", State.READY), State.NEW,
                        Id.PREPARE, State.READY, "audit", new IllegalStateException("audit down")))
                .isAfterCommit().hasPhase(CallPhase.HOOK).hasStatesWritten(StatesWritten.ALL).hasCallSameAs(CALL);
    }

    @Test
    void everyNarrowedViewOfACallFailureReportsAMismatchUnderItsOwnContext() {
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> TransitionFailureAssert.assertThat(new TransitionAbortedException(CALL, CallPhase.ACTION,
                        details(), "no")).isAbort().hasPhase(CallPhase.GUARD)),
                "abort", "phase", CallPhase.GUARD, CallPhase.ACTION);
        MachineCall other = new MachineCall(TestDomain.KEY, new Entity("e", State.NEW), Optional.of(Id.PREPARE),
                false);
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> TransitionFailureAssert.assertThat(new CascadeException(CALL, details(),
                        List.of(elementAbort(new Other("a"))))).isCascadeAbort().hasCallSameAs(other)),
                "cascade abort", "call", other, CALL);
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> TransitionFailureAssert.assertThat(new CommitFailedException(CALL, List.of(), List.of(),
                        new Other("f"), List.of(), new IllegalStateException("a setter threw")))
                        .isCommitFailure().hasCallSameAs(other)),
                "commit failure", "call", other, CALL);
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> TransitionFailureAssert.assertThat(afterCommit(TestDomain.KEY, new Entity("e", State.READY),
                        State.NEW, Id.PREPARE, State.READY, "audit", new IllegalStateException("audit down")))
                        .isAfterCommit().hasStatesWritten(StatesWritten.NONE)),
                "after-commit failure", "states written", StatesWritten.NONE, StatesWritten.ALL);
        CallFailureAssert callView = TransitionFailureAssert.assertThat(
                new CommitFailedException(CALL, List.of(), List.of(), "nothing written")).isCallFailure();
        assertFailure(assertThrows(GraphAssertionError.class, () -> callView.journal().hasSize(1)),
                "call failure.journal", "size", 1, 0);
        CommitFailureAssert commitView = TransitionFailureAssert.assertThat(
                new CommitFailedException(CALL, List.of(), List.of(), "nothing written")).isCommitFailure();
        assertFailure(assertThrows(GraphAssertionError.class, () -> commitView.journal().hasSize(1)),
                "commit failure.journal", "size", 1, 0);
        AfterCommitAssert afterView = TransitionFailureAssert.assertThat(
                afterCommit(TestDomain.KEY, new Entity("e", State.READY), State.NEW, Id.PREPARE, State.READY,
                        "audit", new IllegalStateException("audit down"))).isAfterCommit();
        assertFailure(assertThrows(GraphAssertionError.class, () -> afterView.journal().hasSize(1)),
                "after-commit failure.journal", "size", 1, 0);
    }

    @Test
    void aThrowableThatIsNotACallFailureIsNotReadAsOne() {
        IllegalStateException plain = new IllegalStateException("not a call");
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> TransitionFailureAssert.assertThat(plain).isCallFailure()),
                "failure", "type", CallFailedException.class, plain);
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> TransitionFailureAssert.assertThat(null).isCallFailure()),
                "failure", "type", CallFailedException.class, null);
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
                new AbortDetails(TestDomain.KEY, entity, Optional.of(State.NEW), Optional.of(Id.PREPARE),
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
                () -> InvalidGraphAssert.assertThat(null).hasCodesExactly("SG102")),
                "invalid graph", "codes", List.of("SG102"), null);
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

    // --- fixtures ----------------------------------------------------------------------------------

    private static final MachineKey<Other, Flag, Switch> OTHERS =
            MachineKey.of(Other.class, Flag.class, Switch.class);

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
        return new AbortDetails(TestDomain.KEY, new Entity("e", State.NEW), Optional.of(State.NEW),
                Optional.of(Id.PREPARE), Optional.of(State.READY), Optional.of("prepare"),
                List.of(), List.of());
    }

    private static TransitionAbortedException elementAbort(Other entity) {
        return new TransitionAbortedException(CALL, CallPhase.ACTION, new AbortDetails(OTHERS, entity,
                Optional.of(Flag.OFF), Optional.of(Switch.FLIP), Optional.of(Flag.ON), Optional.empty(), List.of(),
                List.of()), "rejected inside a cascade");
    }
}
