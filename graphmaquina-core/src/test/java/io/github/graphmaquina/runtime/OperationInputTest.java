package io.github.graphmaquina.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.TransitionGuard;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.error.TransitionAbortedException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OperationInputTest {

    enum S { OPEN, DONE, FAILED }

    static final class Entity {
        S state = S.OPEN;
    }

    record Verdict(boolean eligible) { }

    static final MachineKey<Entity, S> KEY = MachineKey.of(Entity.class, S.class);

    private StateMachine<Entity, S> machine(TransitionGuard<Entity, S> guard, TransitionAction<Entity, S> action,
                                            TransitionAction<Entity, S> recovery) {
        GraphDefinition<Entity, S> graph = StateGraph.define(KEY).initial(S.OPEN).terminal(S.DONE).terminal(S.FAILED)
                .from(S.OPEN).to(S.DONE).action("work", action).guard("eligible", guard)
                    .onFailure(S.FAILED).failsOn(IllegalStateException.class)
                    .to(S.FAILED).action("recover", recovery).stay().forbidOthers("closed")
                .from(S.DONE).stay().forbidOthers("done")
                .from(S.FAILED).stay().forbidOthers("failed").build();
        return StateMachines.from(graph).state((Entity e) -> e.state, (Entity e, S s) -> e.state = s)
                .asyncExecutor(Runnable::run).build();
    }

    @Test void eachCallHasItsOwnValueAndAbsenceDoesNotReuseIt() {
        List<Optional<?>> seen = new ArrayList<>();
        StateMachine<Entity, S> m = machine(
                (GuardContext<Entity, S> ctx) -> { seen.add(ctx.input()); return GuardResult.pass(); },
                (TransitionContext<Entity, S> ctx) -> { seen.add(ctx.input()); return ActionOutcome.ok(); },
                (TransitionContext<Entity, S> ctx) -> ActionOutcome.ok());
        Verdict v = new Verdict(true);
        m.transitionTo(new Entity(), S.DONE, v);
        m.transitionTo(new Entity(), S.DONE);
        m.transitionTo(new Entity(), S.DONE, null);
        assertEquals(6, seen.size());
        assertSame(v, seen.get(0).orElseThrow());
        assertSame(v, seen.get(1).orElseThrow());
        assertTrue(seen.subList(2, 6).stream().allMatch(Optional::isEmpty));
    }

    @Test void rejectionKeepsStateAndSkipsAction() {
        Entity e = new Entity();
        StateMachine<Entity, S> m = machine(
                (GuardContext<Entity, S> ctx) -> ctx.input(Verdict.class).orElseThrow().eligible()
                        ? GuardResult.pass() : GuardResult.reject("ineligible"),
                (TransitionContext<Entity, S> ctx) -> { fail("action must not run"); return ActionOutcome.ok(); },
                (TransitionContext<Entity, S> ctx) -> ActionOutcome.ok());
        assertInstanceOf(Outcome.Rejected.class, m.transitionTo(e, S.DONE, new Verdict(false)));
        assertEquals(S.OPEN, e.state);
    }

    @Test void bothFailureTriggersPreserveInputInRecovery() {
        for (boolean throwsFailure : List.of(false, true)) {
            Verdict v = new Verdict(true);
            AtomicReference<Object> recovered = new AtomicReference<>();
            StateMachine<Entity, S> m = machine((GuardContext<Entity, S> ctx) -> GuardResult.pass(),
                    (TransitionContext<Entity, S> ctx) -> {
                        assertSame(v, ctx.input(Verdict.class).orElseThrow());
                        if (throwsFailure) {
                            throw new IllegalStateException("service failed");
                        }
                        return ActionOutcome.failed("declined");
                    },
                    (TransitionContext<Entity, S> ctx) -> {
                        recovered.set(ctx.input().orElseThrow());
                        return ActionOutcome.ok();
                    });
            Entity e = new Entity();
            assertInstanceOf(Outcome.Failed.class, m.transitionTo(e, S.DONE, v));
            assertSame(v, recovered.get());
            assertEquals(S.FAILED, e.state);
        }
    }

    @Test void directCallToTheFailStateDoesNotInheritAPastFailuresInput() {
        AtomicReference<Object> seen = new AtomicReference<>();
        StateMachine<Entity, S> m = machine((GuardContext<Entity, S> ctx) -> GuardResult.pass(),
                (TransitionContext<Entity, S> ctx) -> ActionOutcome.failed("declined"),
                (TransitionContext<Entity, S> ctx) -> { seen.set(ctx.input().orElse(null)); return ActionOutcome.ok(); });
        Verdict fromAFailure = new Verdict(true);
        m.transitionTo(new Entity(), S.DONE, fromAFailure);
        assertSame(fromAFailure, seen.get());
        Verdict direct = new Verdict(false);
        m.transitionTo(new Entity(), S.FAILED, direct);
        assertSame(direct, seen.get());
    }

    @Test void aWrongGuardInputTypeAbortsWithoutCommit() {
        StateMachine<Entity, S> m = machine(
                (GuardContext<Entity, S> ctx) -> { ctx.input(String.class); return GuardResult.pass(); },
                (TransitionContext<Entity, S> ctx) -> ActionOutcome.ok(),
                (TransitionContext<Entity, S> ctx) -> ActionOutcome.ok());
        Entity e = new Entity();
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                () -> m.transitionTo(e, S.DONE, new Verdict(true)));
        assertInstanceOf(ClassCastException.class, error.getCause());
        assertEquals(S.OPEN, e.state);
    }

    @Test void aCascadeFailureRouteAlsoHasNoRootInput() {
        Entity root = new Entity();
        Entity child = new Entity();
        Object value = new Object();
        AtomicInteger recoveries = new AtomicInteger();
        StateMachine<Entity, S> m = machine((GuardContext<Entity, S> ctx) -> GuardResult.pass(),
                (TransitionContext<Entity, S> ctx) -> {
                    if (ctx.depth() == 0) {
                        assertSame(value, ctx.input().orElseThrow());
                        assertInstanceOf(Outcome.Failed.class, ctx.cascade(KEY, child, S.DONE));
                        assertSame(value, ctx.input().orElseThrow());
                        return ActionOutcome.ok();
                    }
                    assertTrue(ctx.input().isEmpty());
                    return ActionOutcome.failed("child failed");
                },
                (TransitionContext<Entity, S> ctx) -> {
                    assertTrue(ctx.input().isEmpty());
                    assertEquals(1, ctx.depth());
                    recoveries.incrementAndGet();
                    return ActionOutcome.ok();
                });
        assertInstanceOf(Outcome.Applied.class, m.transitionTo(root, S.DONE, value));
        assertEquals(S.DONE, root.state);
        assertEquals(S.FAILED, child.state);
        assertEquals(1, recoveries.get());
    }

    @Test void valueGettersRemainReadableAfterReturnAndOnAnotherThread() throws Exception {
        AtomicReference<GuardContext<Entity, S>> guard = new AtomicReference<>();
        AtomicReference<TransitionContext<Entity, S>> action = new AtomicReference<>();
        StateMachine<Entity, S> m = machine(
                (GuardContext<Entity, S> ctx) -> { guard.set(ctx); return GuardResult.pass(); },
                (TransitionContext<Entity, S> ctx) -> { action.set(ctx); return ActionOutcome.ok(); },
                (TransitionContext<Entity, S> ctx) -> ActionOutcome.ok());
        Verdict v = new Verdict(true);
        m.transitionTo(new Entity(), S.DONE, v);
        assertSame(v, guard.get().input().orElseThrow());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            assertSame(v, executor.submit(() -> action.get().input(Verdict.class).orElseThrow()).get(5, TimeUnit.SECONDS));
        }
    }

    @Test void asyncGuardsActionsAndOverlappingCallsKeepInputLocal() throws Exception {
        Entity first = new Entity();
        Entity second = new Entity();
        Verdict a = new Verdict(true);
        Verdict b = new Verdict(false);
        Map<Entity, Verdict> expected = Map.of(first, a, second, b);
        CountDownLatch bothEntered = new CountDownLatch(2);
        CompletableFuture<Void> release = new CompletableFuture<>();
        GraphDefinition<Entity, S> graph = StateGraph.define(KEY).initial(S.OPEN).terminal(S.DONE).terminal(S.FAILED)
                .from(S.OPEN).to(S.DONE).asyncAction("work", (TransitionContext<Entity, S> ctx) -> {
                    assertSame(expected.get(ctx.entity()), ctx.input(Verdict.class).orElseThrow());
                    return CompletableFuture.completedFuture(ActionOutcome.ok());
                }).asyncGuard("eligible", (GuardContext<Entity, S> ctx) -> {
                    Verdict value = ctx.input(Verdict.class).orElseThrow();
                    assertSame(expected.get(ctx.entity()), value);
                    bothEntered.countDown();
                    return release.thenApply((Void ignored) -> GuardResult.pass());
                }).to(S.FAILED).move().stay().forbidOthers("closed")
                .from(S.DONE).stay().forbidOthers("done")
                .from(S.FAILED).stay().forbidOthers("failed").build();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            StateMachine<Entity, S> m = StateMachines.from(graph).state((Entity e) -> e.state, (Entity e, S s) -> e.state = s)
                    .asyncTimeout(Duration.ofSeconds(10)).asyncExecutor(executor).build();
            CompletableFuture<Outcome<Entity, S>> one = m.async().transitionTo(first, S.DONE, a);
            CompletableFuture<Outcome<Entity, S>> two = m.async().transitionTo(second, S.DONE, b);
            try {
                assertTrue(bothEntered.await(5, TimeUnit.SECONDS));
            } finally {
                release.complete(null);
            }
            assertInstanceOf(Outcome.Applied.class, one.get(5, TimeUnit.SECONDS));
            assertInstanceOf(Outcome.Applied.class, two.get(5, TimeUnit.SECONDS));
        }
    }
}
