package io.stategraph.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ValuesTest {

    @Test
    void machineKeyToStringUsesSimpleNames() {
        MachineKey<String, DayOfWeek> key = MachineKey.of(String.class, DayOfWeek.class);
        assertEquals("String/DayOfWeek", key.toString());
        assertEquals(key, new MachineKey<>(String.class, DayOfWeek.class));
    }

    @Test
    void machineKeyRejectsNulls() {
        NullPointerException error = assertThrows(NullPointerException.class,
                () -> new MachineKey<String, DayOfWeek>(null, DayOfWeek.class));
        assertEquals("entity", error.getMessage());
    }

    @Test
    void actionOutcomeFailedRequiresReason() {
        assertEquals(new ActionOutcome.Ok(), ActionOutcome.ok());
        assertEquals(new ActionOutcome.Failed("x"), ActionOutcome.failed("x"));
        assertThrows(IllegalArgumentException.class, () -> ActionOutcome.failed("  "));
        NullPointerException error = assertThrows(NullPointerException.class, () -> ActionOutcome.failed(null));
        assertEquals("reason", error.getMessage());
    }

    @Test
    void guardResultRejectRequiresReason() {
        assertEquals(new GuardResult.Pass(), GuardResult.pass());
        assertEquals(new GuardResult.Reject("x"), GuardResult.reject("x"));
        assertThrows(IllegalArgumentException.class, () -> GuardResult.reject(""));
    }

    @Test
    void actionRefKnowsWhetherItIsAsync() {
        ActionRef<String, DayOfWeek> sync = new ActionRef.Sync<>("A", (TransitionContext<String, DayOfWeek> ctx) -> ActionOutcome.ok());
        ActionRef<String, DayOfWeek> async = new ActionRef.Async<>("B",
                (TransitionContext<String, DayOfWeek> ctx) -> java.util.concurrent.CompletableFuture.completedFuture(ActionOutcome.ok()));
        assertFalse(sync.isAsync());
        assertTrue(async.isAsync());
        assertEquals("A", sync.name());
        assertThrows(IllegalArgumentException.class,
                () -> new ActionRef.Sync<String, DayOfWeek>(" ", (TransitionContext<String, DayOfWeek> ctx) -> ActionOutcome.ok()));
    }

    @Test
    void outcomeCurrentFollowsTheVariant() {
        Outcome<String, DayOfWeek> applied = new Outcome.Applied<>("e", DayOfWeek.MONDAY, DayOfWeek.TUESDAY, List.of(), List.of());
        Outcome<String, DayOfWeek> ignored = new Outcome.Ignored<>("e", DayOfWeek.MONDAY, DayOfWeek.MONDAY);
        Outcome<String, DayOfWeek> rejected = new Outcome.Rejected<>("e", DayOfWeek.MONDAY, DayOfWeek.TUESDAY, "no", Optional.empty());
        Outcome<String, DayOfWeek> failed = new Outcome.Failed<>("e", DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.SUNDAY,
                "declinada", Optional.empty(), List.of(), List.of());
        assertEquals(DayOfWeek.TUESDAY, applied.current());
        assertEquals(DayOfWeek.MONDAY, ignored.current());
        assertEquals(DayOfWeek.MONDAY, rejected.current());
        assertEquals(DayOfWeek.SUNDAY, failed.current());
    }

    @Test
    void namedConditionHelpersCarryTheirName() {
        NamedCondition<String, String, DayOfWeek> all = WatchCondition.allIn(DayOfWeek.FRIDAY);
        assertEquals("allIn FRIDAY", all.name());
        assertEquals("anyIn FRIDAY", WatchCondition.<String, String, DayOfWeek>anyIn(DayOfWeek.FRIDAY).name());
        assertEquals("noneIn FRIDAY", WatchCondition.<String, String, DayOfWeek>noneIn(DayOfWeek.FRIDAY).name());
    }
}
