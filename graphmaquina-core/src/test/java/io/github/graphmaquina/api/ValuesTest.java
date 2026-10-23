package io.github.graphmaquina.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.Month;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Contracts of the small API values: machine keys, action outcomes, guard results, action references, outcomes, state
 * policies and named conditions.
 */
class ValuesTest {

    /** A second transition catalogue over the same state enum, to tell two machine keys apart. */
    enum Other { A }

    @Test
    void machineKeyToStringUsesSimpleNamesAndIdentityIncludesTheCatalogue() {
        MachineKey<String, DayOfWeek, Month> key = MachineKey.of(String.class, DayOfWeek.class, Month.class);
        assertEquals("String/DayOfWeek", key.toString());
        assertEquals(key, new MachineKey<>(String.class, DayOfWeek.class, Month.class));
        assertNotEquals(key, MachineKey.of(String.class, DayOfWeek.class, Other.class));
    }

    @Test
    void machineKeyRejectsNulls() {
        NullPointerException error = assertThrows(NullPointerException.class,
                () -> new MachineKey<String, DayOfWeek, Month>(null, DayOfWeek.class, Month.class));
        assertTrue(error.getMessage().startsWith("entity"), error.getMessage());
    }

    @Test
    void machineKeyRefusesAPrimitiveEntityType() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> MachineKey.of(int.class, DayOfWeek.class, Month.class));
        assertEquals("entity must be a reference type; use its wrapper class", error.getMessage());
    }

    @Test
    void actionOutcomeFailedRequiresReason() {
        assertEquals(new ActionOutcome.Ok(), ActionOutcome.ok());
        assertEquals(new ActionOutcome.Failed("x"), ActionOutcome.failed("x"));
        assertThrows(IllegalArgumentException.class, () -> ActionOutcome.failed("  "));
        NullPointerException error = assertThrows(NullPointerException.class, () -> ActionOutcome.failed(null));
        assertTrue(error.getMessage().startsWith("reason"), error.getMessage());
    }

    @Test
    void guardResultRejectRequiresReason() {
        assertEquals(new GuardResult.Pass(), GuardResult.pass());
        assertEquals(new GuardResult.Reject("x"), GuardResult.reject("x"));
        assertThrows(IllegalArgumentException.class, () -> GuardResult.reject(""));
    }

    @Test
    void actionRefKnowsWhetherItIsAsync() {
        ActionRef<String, DayOfWeek, Month> sync =
                new ActionRef.Sync<>("A", (TransitionContext<String, DayOfWeek, Month> ctx) -> ActionOutcome.ok());
        ActionRef<String, DayOfWeek, Month> async = new ActionRef.Async<>("B",
                (TransitionContext<String, DayOfWeek, Month> ctx) ->
                        java.util.concurrent.CompletableFuture.completedFuture(ActionOutcome.ok()));
        assertFalse(sync.isAsync());
        assertTrue(async.isAsync());
        assertEquals("A", sync.name());
        assertThrows(IllegalArgumentException.class, () -> new ActionRef.Sync<String, DayOfWeek, Month>(" ",
                (TransitionContext<String, DayOfWeek, Month> ctx) -> ActionOutcome.ok()));
    }

    @Test
    void outcomeRecordedStateAndRequestFollowTheVariant() {
        Outcome<String, DayOfWeek, Month> applied = new Outcome.Applied<>("e", DayOfWeek.MONDAY, Month.JANUARY,
                DayOfWeek.TUESDAY, List.of(), List.of());
        Outcome<String, DayOfWeek, Month> ignored =
                new Outcome.Ignored<>("e", DayOfWeek.MONDAY, IgnoreReason.NO_RULE_MATCHED);
        Outcome<String, DayOfWeek, Month> rejected = new Outcome.Rejected<>("e", DayOfWeek.MONDAY, Month.JANUARY, "no",
                RejectionKind.NO_TRANSITION, Optional.empty());
        Outcome<String, DayOfWeek, Month> failed = new Outcome.Failed<>("e", DayOfWeek.MONDAY, Month.JANUARY,
                DayOfWeek.TUESDAY, Month.MARCH, DayOfWeek.SUNDAY, "declined", Optional.empty(), List.of(), List.of());
        assertEquals(DayOfWeek.TUESDAY, applied.recordedState());
        assertEquals(DayOfWeek.MONDAY, ignored.recordedState());
        assertEquals(DayOfWeek.MONDAY, rejected.recordedState());
        assertEquals(DayOfWeek.SUNDAY, failed.recordedState());
        assertEquals(Optional.of(Month.JANUARY), applied.requestedTransition());
        assertEquals(Optional.empty(), ignored.requestedTransition());
        assertEquals(Optional.of(Month.JANUARY), rejected.requestedTransition());
        assertEquals(Optional.of(Month.JANUARY), failed.requestedTransition());
    }

    /** A guard rejection names its guard; an absent edge never does. */
    @Test
    void rejectionKindAndGuardAreConsistent() {
        assertThrows(IllegalArgumentException.class, () -> new Outcome.Rejected<String, DayOfWeek, Month>("e",
                DayOfWeek.MONDAY, Month.JANUARY, "no", RejectionKind.NO_TRANSITION, Optional.of("G")));
        assertThrows(IllegalArgumentException.class, () -> new Outcome.Rejected<String, DayOfWeek, Month>("e",
                DayOfWeek.MONDAY, Month.JANUARY, "no", RejectionKind.GUARD, Optional.empty()));
    }

    @Test
    void statePolicyFallsBackToItsDefaultReason() {
        StatePolicy<DayOfWeek, Month> policy = new StatePolicy<>(DayOfWeek.MONDAY, "not from here",
                Map.of(Month.JANUARY, "January is closed"));
        assertEquals("January is closed", policy.reasonFor(Month.JANUARY));
        assertEquals("not from here", policy.reasonFor(Month.MARCH));
        assertThrows(IllegalArgumentException.class,
                () -> new StatePolicy<DayOfWeek, Month>(DayOfWeek.MONDAY, " ", Map.of()));
    }

    /** A reason declared for one identifier is read like the default one, so it is refused blank the same way. */
    @Test
    void statePolicyRefusesABlankReasonForAnIdentifier() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new StatePolicy<DayOfWeek, Month>(DayOfWeek.MONDAY, "not from here",
                        Map.of(Month.JANUARY, "  ")));
        assertEquals("the policy of MONDAY has a blank reason", error.getMessage());
    }

    /** The reason is what the caller reads about a refusal: a blank one says nothing. */
    @Test
    void rejectedRefusesABlankReason() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new Outcome.Rejected<String, DayOfWeek, Month>("e", DayOfWeek.MONDAY, Month.JANUARY, " ",
                        RejectionKind.NO_TRANSITION, Optional.empty()));
        assertEquals("reason must not be blank", error.getMessage());
    }

    /** The reason is what the caller reads about a routed failure: a blank one says nothing. */
    @Test
    void failedRefusesABlankReason() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new Outcome.Failed<String, DayOfWeek, Month>("e", DayOfWeek.MONDAY, Month.JANUARY,
                        DayOfWeek.TUESDAY, Month.MARCH, DayOfWeek.SUNDAY, "", Optional.empty(), List.of(), List.of()));
        assertEquals("reason must not be blank", error.getMessage());
    }

    @Test
    void namedConditionHelpersCarryTheirName() {
        NamedCondition<String, String, DayOfWeek, Month> all = WatchCondition.allIn(DayOfWeek.FRIDAY);
        assertEquals("allIn FRIDAY", all.name());
        assertEquals("anyIn FRIDAY", WatchCondition.<String, String, DayOfWeek, Month>anyIn(DayOfWeek.FRIDAY).name());
        assertEquals("noneIn FRIDAY", WatchCondition.<String, String, DayOfWeek, Month>noneIn(DayOfWeek.FRIDAY).name());
    }
}
