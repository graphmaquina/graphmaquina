package io.github.graphmaquina.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.MachineKey;
import java.time.DayOfWeek;
import java.time.Month;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** What the exception types carry and what their messages name, built by hand without running a machine. */
class ExceptionsTest {

    private static final MachineKey<String, DayOfWeek, Month> KEY =
            MachineKey.of(String.class, DayOfWeek.class, Month.class);

    private static AbortDetails details() {
        return new AbortDetails(KEY, "e", DayOfWeek.MONDAY, Optional.of(Month.JANUARY), Optional.of(DayOfWeek.TUESDAY),
                Optional.of("A"), List.of("String/DayOfWeek#e"), List.of());
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
    void abortedCarriesDetailsAndCause() {
        IllegalStateException cause = new IllegalStateException("boom");
        TransitionAbortedException error = new TransitionAbortedException(details(), "the action threw", cause);
        assertSame(cause, error.getCause());
        assertEquals(DayOfWeek.MONDAY, error.from());
        assertEquals(Optional.of(Month.JANUARY), error.transition());
        assertEquals(Optional.of(DayOfWeek.TUESDAY), error.to());
        assertEquals(Optional.of("A"), error.action());
        assertTrue(error.getMessage().contains("MONDAY -JANUARY-> TUESDAY"));
        assertTrue(error.getMessage().contains("[route String/DayOfWeek#e]"));
    }

    /** Before an edge is resolved there is no destination to report, and none is invented. */
    @Test
    void detailsBeforeResolutionCarryNeitherIdentityNorDestination() {
        AbortDetails unresolved = new AbortDetails(KEY, "e", DayOfWeek.MONDAY, Optional.empty(), Optional.empty(),
                Optional.empty(), List.of(), List.of());
        assertEquals("MONDAY", unresolved.describeEdge());
        TransitionAbortedException error = new TransitionAbortedException(unresolved, "no watch left");
        assertEquals(Optional.empty(), error.to());
        assertTrue(error.getMessage().contains("String/DayOfWeek MONDAY: no watch left"));

        AbortDetails requested = new AbortDetails(KEY, "e", DayOfWeek.MONDAY, Optional.of(Month.JANUARY),
                Optional.empty(), Optional.empty(), List.of(), List.of());
        assertEquals("MONDAY -JANUARY-> ?", requested.describeEdge());
    }

    @Test
    void subtypesKeepPayload() {
        UnhandledFailureException unhandled = new UnhandledFailureException(details(), "declined");
        assertEquals("declined", unhandled.reason());
        CascadeCycleException cycle =
                new CascadeCycleException(details(), List.of("a", "b", "a"), Month.MAY, Month.JULY);
        assertTrue(cycle.getMessage().contains("a → b → a"));
        assertEquals(Month.MAY, cycle.inFlightTransition());
        assertEquals(Month.JULY, cycle.requestedTransition());
        PoisonedUnitOfWorkException poisoned = new PoisonedUnitOfWorkException(details(), unhandled);
        assertSame(unhandled, poisoned.original());
        assertSame(unhandled, poisoned.getCause());
        CascadeException collected = new CascadeException(details(), List.of(unhandled));
        assertEquals(1, collected.errors().size());
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
    void commitFailedSeparatesAppliedAndPending() {
        CommitFailedException error = new CommitFailedException(List.of("a"), List.of("b", "c"),
                new IllegalStateException("setter"));
        assertEquals(List.of("a"), error.applied());
        assertEquals(List.of("b", "c"), error.pending());
        assertThrows(NullPointerException.class, () -> new StateMachineException(null));
    }

    @Test
    void afterCommitCarriesStepAndCause() {
        IllegalStateException cause = new IllegalStateException("ledger down");
        AfterCommitException error =
                new AfterCommitException(KEY, "e", DayOfWeek.MONDAY, DayOfWeek.TUESDAY, "bookHold", cause);
        assertSame(cause, error.getCause());
        assertEquals("bookHold", error.step());
        assertEquals(DayOfWeek.TUESDAY, error.to());
        assertTrue(error.getMessage().contains("MONDAY → TUESDAY"));
        assertTrue(error.getMessage().contains("bookHold"));
    }
}
