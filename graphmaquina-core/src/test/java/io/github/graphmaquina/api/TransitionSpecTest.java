package io.github.graphmaquina.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Month;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * The checks {@link TransitionSpec} makes when an edge is built, and the kind, key and asynchrony it derives from its
 * parts.
 */
class TransitionSpecTest {

    private static final ActionRef<String, DayOfWeek, Month> SYNC =
            new ActionRef.Sync<>("A", (TransitionContext<String, DayOfWeek, Month> ctx) -> ActionOutcome.ok());
    private static final ActionRef<String, DayOfWeek, Month> ASYNC =
            new ActionRef.Async<>("B", (TransitionContext<String, DayOfWeek, Month> ctx) ->
                    CompletableFuture.completedFuture(ActionOutcome.ok()));

    private static TransitionSpec<String, DayOfWeek, Month> spec(Optional<ActionRef<String, DayOfWeek, Month>> action,
                                                                 List<GuardRef<String, DayOfWeek, Month>> guards,
                                                                 Optional<Month> onFailure, Optional<Duration> timeout) {
        return new TransitionSpec<>(DayOfWeek.MONDAY, Month.JANUARY, DayOfWeek.TUESDAY, action, guards, onFailure,
                List.of(), timeout, Optional.empty());
    }

    @Test
    void anEdgeWithoutAnActionIsAMoveAndKeepsItsKey() {
        TransitionSpec<String, DayOfWeek, Month> move = spec(Optional.empty(), List.of(), Optional.empty(), Optional.empty());
        assertEquals(TransitionKind.MOVE, move.kind());
        assertEquals(new TransitionKey<>(DayOfWeek.MONDAY, Month.JANUARY), move.key());
        assertEquals(TransitionKind.ACTION, spec(Optional.of(SYNC), List.of(), Optional.empty(), Optional.empty()).kind());
    }

    @Test
    void onFailureAndFailsOnRequireAnAction() {
        assertThrows(IllegalArgumentException.class,
                () -> spec(Optional.empty(), List.of(), Optional.of(Month.MARCH), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new TransitionSpec<String, DayOfWeek, Month>(
                DayOfWeek.MONDAY, Month.JANUARY, DayOfWeek.TUESDAY, Optional.empty(), List.of(), Optional.empty(),
                List.of(IllegalStateException.class), Optional.empty(), Optional.empty()));
    }

    @Test
    void timeoutOnlyOnAsyncActionAndPositive() {
        assertThrows(IllegalArgumentException.class,
                () -> spec(Optional.of(SYNC), List.of(), Optional.empty(), Optional.of(Duration.ofSeconds(1))));
        assertThrows(IllegalArgumentException.class,
                () -> spec(Optional.of(ASYNC), List.of(), Optional.empty(), Optional.of(Duration.ZERO)));
        TransitionSpec<String, DayOfWeek, Month> ok =
                spec(Optional.of(ASYNC), List.of(), Optional.empty(), Optional.of(Duration.ofSeconds(1)));
        assertTrue(ok.isAsync());
    }

    @Test
    void asyncGuardMakesTheEdgeAsync() {
        GuardRef<String, DayOfWeek, Month> guard = new GuardRef.Async<>("G",
                (GuardContext<String, DayOfWeek, Month> ctx) -> CompletableFuture.completedFuture(GuardResult.pass()));
        assertTrue(spec(Optional.empty(), List.of(guard), Optional.empty(), Optional.empty()).isAsync());
        assertFalse(spec(Optional.empty(), List.of(), Optional.empty(), Optional.empty()).isAsync());
    }

    /** A self-loop is an ordinary edge in 2.0: it needs no special kind and no special construction. */
    @Test
    void aSelfLoopIsAnOrdinaryEdge() {
        TransitionSpec<String, DayOfWeek, Month> loop = new TransitionSpec<>(DayOfWeek.MONDAY, Month.JANUARY,
                DayOfWeek.MONDAY, Optional.of(SYNC), List.of(), Optional.empty(), List.of(), Optional.empty(),
                Optional.empty());
        assertEquals(DayOfWeek.MONDAY, loop.to());
        assertEquals(TransitionKind.ACTION, loop.kind());
    }

    /** An edge without an action may carry a hook, and an edge built without one reports none. */
    @Test
    void afterCommitIsAllowedOnEveryEdge() {
        HookRef<String, DayOfWeek, Month> hook = HookRef.of("H", (String e) -> { });
        TransitionSpec<String, DayOfWeek, Month> move = new TransitionSpec<>(DayOfWeek.MONDAY, Month.JANUARY,
                DayOfWeek.TUESDAY, Optional.empty(), List.of(), Optional.empty(), List.of(), Optional.empty(),
                Optional.of(hook));
        assertTrue(move.afterCommit().isPresent());
        assertTrue(spec(Optional.empty(), List.of(), Optional.empty(), Optional.empty()).afterCommit().isEmpty());
    }
}
