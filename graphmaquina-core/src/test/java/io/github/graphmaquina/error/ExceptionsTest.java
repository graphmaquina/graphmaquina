package io.github.graphmaquina.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionRecord;
import io.github.graphmaquina.api.ActionStatus;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.MachineCall;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.StatesWritten;
import io.github.graphmaquina.api.SubmachineStart;
import io.github.graphmaquina.api.TransitionKind;
import io.github.graphmaquina.api.TransitionRecord;
import java.time.DayOfWeek;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** What the exception types carry and what their messages name, built by hand without running a machine. */
class ExceptionsTest {

    private static final MachineKey<String, DayOfWeek, Month> KEY =
            MachineKey.of(String.class, DayOfWeek.class, Month.class);
    private static final MachineCall CALL = new MachineCall(KEY, "e", Optional.of(Month.JANUARY), false);
    private static final ActionRecord RAN =
            new ActionRecord(KEY, "e", DayOfWeek.MONDAY, Month.JANUARY, DayOfWeek.TUESDAY, "A", 0, ActionStatus.OK);

    private static AbortDetails details() {
        return new AbortDetails(KEY, "e", Optional.of(DayOfWeek.MONDAY), Optional.of(Month.JANUARY),
                Optional.of(DayOfWeek.TUESDAY), Optional.of("A"), List.of("String/DayOfWeek#e"), List.of(RAN));
    }

    @Test
    void invalidGraphListsProblemsInMessageAndCodes() {
        InvalidGraphException error = new InvalidGraphException(KEY, List.of(
                new InvalidGraphException.Problem("SG102", "duplicate transition"),
                new InvalidGraphException.Problem("SG201", "no initial")));
        assertEquals(List.of("SG102", "SG201"), error.codes());
        assertTrue(error.getMessage().contains("[SG102] duplicate transition"));
        assertTrue(error.getMessage().startsWith("the graph String/DayOfWeek has 2 problem(s):"));
    }

    @Test
    void abortedCarriesItsCallItsPhaseDetailsAndCause() {
        IllegalStateException cause = new IllegalStateException("boom");
        TransitionAbortedException error =
                new TransitionAbortedException(CALL, CallPhase.ACTION, details(), "the action threw", cause);
        assertSame(CALL, error.call());
        assertEquals(CallPhase.ACTION, error.phase());
        assertEquals(StatesWritten.NONE, error.statesWritten());
        assertEquals(List.of(RAN), error.journal());
        assertSame(cause, error.getCause());
        assertEquals(Optional.of(DayOfWeek.MONDAY), error.from());
        assertEquals(Optional.of(Month.JANUARY), error.transition());
        assertEquals(Optional.of(DayOfWeek.TUESDAY), error.to());
        assertEquals(Optional.of("A"), error.action());
        assertTrue(error.getMessage().contains("MONDAY -JANUARY-> TUESDAY"));
        assertTrue(error.getMessage().contains("[route String/DayOfWeek#e]"));
    }

    @Test
    void anAbortHappensBeforeTheCommit() {
        for (CallPhase phase : List.of(CallPhase.COMMIT, CallPhase.HOOK, CallPhase.REACTIONS)) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> new TransitionAbortedException(CALL, phase, details(), "no"));
            assertEquals("an abort happens before the commit, not in " + phase, refused.getMessage());
        }
        assertThrows(NullPointerException.class,
                () -> new TransitionAbortedException(null, CallPhase.ACTION, details(), "no"));
        assertThrows(NullPointerException.class, () -> new TransitionAbortedException(CALL, null, details(), "no"));
    }

    /** Before an edge is resolved there is no destination to report, and none is invented. */
    @Test
    void detailsBeforeResolutionCarryNeitherIdentityNorDestination() {
        AbortDetails unresolved = new AbortDetails(KEY, "e", Optional.of(DayOfWeek.MONDAY), Optional.empty(),
                Optional.empty(), Optional.empty(), List.of(), List.of());
        assertEquals("MONDAY", unresolved.describeEdge());
        TransitionAbortedException error =
                new TransitionAbortedException(CALL, CallPhase.RESOLVE, unresolved, "no watch left");
        assertEquals(Optional.empty(), error.to());
        assertTrue(error.getMessage().contains("String/DayOfWeek MONDAY: no watch left"));

        AbortDetails requested = new AbortDetails(KEY, "e", Optional.of(DayOfWeek.MONDAY), Optional.of(Month.JANUARY),
                Optional.empty(), Optional.empty(), List.of(), List.of());
        assertEquals("MONDAY -JANUARY-> ?", requested.describeEdge());
    }

    /** Before the state was read there is no state to report either. */
    @Test
    void detailsBeforeTheStateReadReportAQuestionMarkForIt() {
        AbortDetails unread = new AbortDetails(KEY, "e", Optional.empty(), Optional.of(Month.JANUARY), Optional.empty(),
                Optional.empty(), List.of(), List.of());
        assertEquals("? -JANUARY-> ?", unread.describeEdge());
        AbortDetails reevaluation = new AbortDetails(KEY, "e", Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), List.of(), List.of());
        assertEquals("?", reevaluation.describeEdge());
    }

    @Test
    void subtypesKeepPayloadAndTheirPhase() {
        UnhandledFailureException unhandled = new UnhandledFailureException(CALL, details(), "declined");
        assertEquals("declined", unhandled.reason());
        assertEquals(CallPhase.ACTION, unhandled.phase());
        CascadeCycleException cycle =
                new CascadeCycleException(CALL, details(), List.of("a", "b", "a"), Month.MAY, Month.JULY);
        assertTrue(cycle.getMessage().contains("a → b → a"));
        assertEquals(Month.MAY, cycle.inFlightTransition());
        assertEquals(Month.JULY, cycle.requestedTransition());
        assertEquals(CallPhase.ACTION, cycle.phase());
        PoisonedUnitOfWorkException poisoned = new PoisonedUnitOfWorkException(CALL, details(), unhandled);
        assertSame(unhandled, poisoned.original());
        assertSame(unhandled, poisoned.getCause());
        CascadeException collected = new CascadeException(CALL, details(), List.of(unhandled));
        assertEquals(1, collected.errors().size());
        assertEquals(CallPhase.ACTION, new CascadeDepthExceededException(CALL, details(), 3).phase());
        assertEquals(CallPhase.RESOLVE, new WatchChainExceededException(CALL, details(), 3).phase());
    }

    @Test
    void unknownPeerListsRegistered() {
        UnknownPeerMachineException error = new UnknownPeerMachineException(KEY, List.of());
        assertTrue(error.getMessage().contains("String/DayOfWeek"));
        assertEquals(KEY, error.requested());
    }

    @Test
    void unknownFacadeListsRegistered() {
        UnknownFacadeException error = new UnknownFacadeException(String.class, List.of(Integer.class));
        assertTrue(error.getMessage().contains("java.lang.String"));
        assertTrue(error.getMessage().contains("java.lang.Integer"));
        assertEquals(String.class, error.requested());
    }

    @Test
    void commitFailedSeparatesAppliedFailedAndPending() {
        IllegalStateException setter = new IllegalStateException("setter");
        CommitFailedException partial =
                new CommitFailedException(CALL, List.of(RAN), List.of("a"), "b", List.of("c"), setter);
        assertEquals(List.of("a"), partial.applied());
        assertEquals(Optional.of("b"), partial.failedEntity());
        assertEquals(List.of("c"), partial.pending());
        assertEquals(CallPhase.COMMIT, partial.phase());
        assertEquals(StatesWritten.PARTIAL, partial.statesWritten());
        assertEquals(List.of(RAN), partial.journal());
        assertEquals("phase 2 failed: a setter threw " + setter + "; applied 1, failed 1, pending 1",
                partial.getMessage());
        CommitFailedException none = new CommitFailedException(CALL, List.of(), List.of("a", "b"), "the state moved");
        assertEquals(StatesWritten.NONE, none.statesWritten());
        assertEquals(Optional.empty(), none.failedEntity());
        assertEquals("phase 2 failed: the state moved; applied 0, pending 2", none.getMessage());
        assertThrows(NullPointerException.class, () -> new StateMachineException(null));
    }

    @Test
    void afterCommitCarriesTheHookTheHopAndTheRecords() {
        IllegalStateException cause = new IllegalStateException("ledger down");
        TransitionRecord<String, DayOfWeek, Month> hop = new TransitionRecord<>(KEY, "e", DayOfWeek.MONDAY,
                Month.JANUARY, DayOfWeek.TUESDAY, TransitionKind.ACTION, Optional.of("A"), 0);
        AfterCommitException error = new AfterCommitException(CALL, List.of(RAN), List.of(hop), hop, "bookHold", cause);
        assertSame(cause, error.getCause());
        assertEquals("bookHold", error.hook());
        assertSame(hop, error.hop());
        assertEquals(List.of(hop), error.records());
        assertEquals(CallPhase.HOOK, error.phase());
        assertEquals(StatesWritten.ALL, error.statesWritten());
        assertEquals(List.of(RAN), error.journal());
        assertEquals("the states of String/DayOfWeek JANUARY are already written, but the hook 'bookHold' failed",
                error.getMessage());
        assertEquals(List.of(), error.starts());
    }

    @Test
    void afterCommitCarriesTheStartsOfTheCall() {
        TransitionRecord<String, DayOfWeek, Month> hop = new TransitionRecord<>(KEY, "e", DayOfWeek.MONDAY,
                Month.JANUARY, DayOfWeek.TUESDAY, TransitionKind.MOVE, Optional.empty(), 0);
        MachineKey<Integer, Month, DayOfWeek> child = MachineKey.of(Integer.class, Month.class, DayOfWeek.class);
        SubmachineStart<Integer, Month, DayOfWeek> start = new SubmachineStart<>(child, 7, Month.MAY, Month.JANUARY,
                KEY, "e", DayOfWeek.TUESDAY, 0);
        List<SubmachineStart<?, ?, ?>> starts = new ArrayList<>(List.of(start));
        AfterCommitException error = new AfterCommitException(CALL, List.of(), List.of(hop), starts, hop, "audit",
                new IllegalStateException("down"));
        starts.clear();
        assertEquals(List.of(start), error.starts());
        assertThrows(UnsupportedOperationException.class, () -> error.starts().clear());
        assertEquals(List.of(hop), error.records());
    }

    /** It names the child machine and both bindings, never an entity. */
    @Test
    void ambiguousParentNamesTheChildAndBothBindings() {
        MachineKey<Integer, Month, DayOfWeek> parent = MachineKey.of(Integer.class, Month.class, DayOfWeek.class);
        AmbiguousParentException error = new AmbiguousParentException(KEY, parent, Month.MARCH, parent, Month.MAY);
        assertEquals(KEY, error.child());
        assertEquals(List.of(parent, parent), error.parents());
        assertEquals(List.of(Month.MARCH, Month.MAY), error.nodes());
        assertEquals("String/DayOfWeek is bound to two active parents: Integer/Month in MARCH and Integer/Month in MAY",
                error.getMessage());
        assertThrows(UnsupportedOperationException.class, () -> error.parents().clear());
        assertThrows(NullPointerException.class,
                () -> new AmbiguousParentException(KEY, parent, Month.MARCH, parent, null));
    }
}
