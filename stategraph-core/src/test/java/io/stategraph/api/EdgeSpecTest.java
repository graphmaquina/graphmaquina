package io.stategraph.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class EdgeSpecTest {

    private static final ActionRef<String, DayOfWeek> SYNC =
            new ActionRef.Sync<>("A", (TransitionContext<String, DayOfWeek> ctx) -> ActionOutcome.ok());
    private static final ActionRef<String, DayOfWeek> ASYNC =
            new ActionRef.Async<>("B", (TransitionContext<String, DayOfWeek> ctx) -> CompletableFuture.completedFuture(ActionOutcome.ok()));

    private static EdgeSpec<String, DayOfWeek> spec(EdgeKind kind, String reason, Optional<ActionRef<String, DayOfWeek>> action,
                                                    List<GuardRef<String, DayOfWeek>> guards, Optional<DayOfWeek> onFailure,
                                                    Optional<Duration> timeout) {
        return new EdgeSpec<>(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, kind, reason, action, guards, onFailure, List.of(), timeout);
    }

    @Test
    void actionRequiresAnActionAndOthersForbidIt() {
        assertThrows(IllegalArgumentException.class,
                () -> spec(EdgeKind.ACTION, "", Optional.empty(), List.of(), Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> spec(EdgeKind.MOVE, "", Optional.of(SYNC), List.of(), Optional.empty(), Optional.empty()));
    }

    @Test
    void forbidRequiresReasonAndRejectsGuards() {
        assertThrows(IllegalArgumentException.class,
                () -> spec(EdgeKind.FORBID, " ", Optional.empty(), List.of(), Optional.empty(), Optional.empty()));
        GuardRef<String, DayOfWeek> guard = new GuardRef.Sync<>("G", (GuardContext<String, DayOfWeek> ctx) -> GuardResult.pass());
        assertThrows(IllegalArgumentException.class,
                () -> spec(EdgeKind.FORBID, "no", Optional.empty(), List.of(guard), Optional.empty(), Optional.empty()));
    }

    @Test
    void onFailureOnlyOnAction() {
        assertThrows(IllegalArgumentException.class,
                () -> spec(EdgeKind.MOVE, "", Optional.empty(), List.of(), Optional.of(DayOfWeek.SUNDAY), Optional.empty()));
    }

    @Test
    void moveCannotBeASelfLoop() {
        assertThrows(IllegalArgumentException.class, () -> new EdgeSpec<String, DayOfWeek>(DayOfWeek.MONDAY, DayOfWeek.MONDAY,
                EdgeKind.MOVE, "", Optional.empty(), List.of(), Optional.empty(), List.of(), Optional.empty()));
    }

    @Test
    void timeoutOnlyOnAsyncActionAndPositive() {
        assertThrows(IllegalArgumentException.class,
                () -> spec(EdgeKind.ACTION, "", Optional.of(SYNC), List.of(), Optional.empty(), Optional.of(Duration.ofSeconds(1))));
        assertThrows(IllegalArgumentException.class,
                () -> spec(EdgeKind.ACTION, "", Optional.of(ASYNC), List.of(), Optional.empty(), Optional.of(Duration.ZERO)));
        EdgeSpec<String, DayOfWeek> ok = spec(EdgeKind.ACTION, "", Optional.of(ASYNC), List.of(), Optional.empty(), Optional.of(Duration.ofSeconds(1)));
        assertTrue(ok.isAsync());
        assertTrue(ok.moves());
    }

    @Test
    void asyncGuardMakesTheEdgeAsync() {
        GuardRef<String, DayOfWeek> guard = new GuardRef.Async<>("G",
                (GuardContext<String, DayOfWeek> ctx) -> CompletableFuture.completedFuture(GuardResult.pass()));
        EdgeSpec<String, DayOfWeek> move = spec(EdgeKind.MOVE, "", Optional.empty(), List.of(guard), Optional.empty(), Optional.empty());
        assertTrue(move.isAsync());
        EdgeSpec<String, DayOfWeek> stay = new EdgeSpec<>(DayOfWeek.MONDAY, DayOfWeek.MONDAY, EdgeKind.STAY, "",
                Optional.empty(), List.of(), Optional.empty(), List.of(), Optional.empty());
        assertFalse(stay.isAsync());
        assertFalse(stay.moves());
    }

    @Test
    void afterCommitOnlyOnActionOrMove() {
        HookRef<String> hook = new HookRef<>("H", (String e) -> { });
        assertThrows(IllegalArgumentException.class, () -> new EdgeSpec<String, DayOfWeek>(DayOfWeek.MONDAY, DayOfWeek.MONDAY,
                EdgeKind.STAY, "", Optional.empty(), List.of(), Optional.empty(), List.of(), Optional.empty(), Optional.of(hook)));
        assertThrows(IllegalArgumentException.class, () -> new EdgeSpec<String, DayOfWeek>(DayOfWeek.MONDAY, DayOfWeek.TUESDAY,
                EdgeKind.FORBID, "no", Optional.empty(), List.of(), Optional.empty(), List.of(), Optional.empty(), Optional.of(hook)));
        EdgeSpec<String, DayOfWeek> move = new EdgeSpec<>(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, EdgeKind.MOVE, "",
                Optional.empty(), List.of(), Optional.empty(), List.of(), Optional.empty(), Optional.of(hook));
        assertTrue(move.afterCommit().isPresent());
        EdgeSpec<String, DayOfWeek> legacy = spec(EdgeKind.MOVE, "", Optional.empty(), List.of(), Optional.empty(), Optional.empty());
        assertTrue(legacy.afterCommit().isEmpty());
    }
}
