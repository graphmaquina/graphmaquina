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
import io.github.graphmaquina.api.PeerView;
import io.github.graphmaquina.api.Reaction;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.TransitionGuard;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.error.TransitionAbortedException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
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

/**
 * The per-call input of {@code execute(entity, id, input)}: guards, actions and the recovery of a failure route read
 * the call's own value, an absent input reads as empty and a null one is refused before the call starts, and neither
 * a cascaded call, a cascade's failure route nor a watch reevaluation inherits it. A wrong input type aborts the
 * call, the value stays readable after the call and on another thread, and overlapping asynchronous calls keep theirs
 * apart.
 */
class OperationInputTest {

    /** States of the entity machine. */
    enum S { OPEN, DONE, FAILED }

    /** Transition identifiers of the entity machine: WORK, its recovery, and the one a watch rule runs. */
    enum T { WORK, RECOVER, WATCH_IT }

    /** The entity: a single mutable state, OPEN at the start. */
    static final class Entity {
        S state = S.OPEN;
    }

    /** The value the tests pass as input: a record of one flag. */
    record Verdict(boolean eligible) { }

    static final MachineKey<Entity, S, T> KEY = MachineKey.of(Entity.class, S.class, T.class);

    private StateMachine<Entity, S, T> machine(TransitionGuard<Entity, S, T> guard,
                                               TransitionAction<Entity, S, T> action,
                                               TransitionAction<Entity, S, T> recovery) {
        GraphDefinition<Entity, S, T> graph = StateGraph.define(KEY).initial(S.OPEN).terminal(S.DONE).terminal(S.FAILED)
                .statePolicy(S.OPEN, "closed")
                .transition(T.WORK, S.OPEN, S.DONE).action("work", action).guard("eligible", guard)
                    .onFailure(T.RECOVER).failsOn(IllegalStateException.class).endTransition()
                .transition(T.RECOVER, S.OPEN, S.FAILED).action("recover", recovery).endTransition()
                .build();
        return StateMachines.from(graph).state((Entity e) -> e.state, (Entity e, S s) -> e.state = s)
                .asyncExecutor(Runnable::run).build();
    }

    @Test void eachCallHasItsOwnValueAndAbsenceDoesNotReuseIt() {
        List<Optional<?>> seen = new ArrayList<>();
        StateMachine<Entity, S, T> m = machine(
                (GuardContext<Entity, S, T> ctx) -> { seen.add(ctx.input()); return GuardResult.pass(); },
                (TransitionContext<Entity, S, T> ctx) -> { seen.add(ctx.input()); return ActionOutcome.ok(); },
                (TransitionContext<Entity, S, T> ctx) -> ActionOutcome.ok());
        Verdict v = new Verdict(true);
        m.execute(new Entity(), T.WORK, v);
        m.execute(new Entity(), T.WORK);
        assertEquals(4, seen.size());
        assertSame(v, seen.get(0).orElseThrow());
        assertSame(v, seen.get(1).orElseThrow());
        assertTrue(seen.subList(2, 4).stream().allMatch(Optional::isEmpty));
    }

    /**
     * A null input is no absent one: execute and the execute of the async view refuse it on the calling thread, before
     * the call starts, so no guard or action runs and the entity keeps its state.
     */
    @Test void aNullInputIsRefusedBeforeTheCallStarts() {
        List<Optional<?>> seen = new ArrayList<>();
        StateMachine<Entity, S, T> m = machine(
                (GuardContext<Entity, S, T> ctx) -> { seen.add(ctx.input()); return GuardResult.pass(); },
                (TransitionContext<Entity, S, T> ctx) -> { seen.add(ctx.input()); return ActionOutcome.ok(); },
                (TransitionContext<Entity, S, T> ctx) -> ActionOutcome.ok());
        Entity e = new Entity();
        assertEquals("input is marked non-null but is null",
                assertThrows(NullPointerException.class, () -> m.execute(e, T.WORK, null)).getMessage());
        assertEquals("input is marked non-null but is null",
                assertThrows(NullPointerException.class, () -> m.async().execute(e, T.WORK, null)).getMessage());
        assertEquals(List.of(), seen);
        assertEquals(S.OPEN, e.state);
    }

    @Test void rejectionKeepsStateAndSkipsAction() {
        Entity e = new Entity();
        StateMachine<Entity, S, T> m = machine(
                (GuardContext<Entity, S, T> ctx) -> ctx.input(Verdict.class).orElseThrow().eligible()
                        ? GuardResult.pass() : GuardResult.reject("ineligible"),
                (TransitionContext<Entity, S, T> ctx) -> { fail("action must not run"); return ActionOutcome.ok(); },
                (TransitionContext<Entity, S, T> ctx) -> ActionOutcome.ok());
        assertInstanceOf(Outcome.Rejected.class, m.execute(e, T.WORK, new Verdict(false)));
        assertEquals(S.OPEN, e.state);
    }

    @Test void bothFailureTriggersPreserveInputInRecovery() {
        for (boolean throwsFailure : List.of(false, true)) {
            Verdict v = new Verdict(true);
            AtomicReference<Object> recovered = new AtomicReference<>();
            StateMachine<Entity, S, T> m = machine((GuardContext<Entity, S, T> ctx) -> GuardResult.pass(),
                    (TransitionContext<Entity, S, T> ctx) -> {
                        assertSame(v, ctx.input(Verdict.class).orElseThrow());
                        if (throwsFailure) {
                            throw new IllegalStateException("service failed");
                        }
                        return ActionOutcome.failed("declined");
                    },
                    (TransitionContext<Entity, S, T> ctx) -> {
                        recovered.set(ctx.input().orElseThrow());
                        return ActionOutcome.ok();
                    });
            Entity e = new Entity();
            assertInstanceOf(Outcome.Failed.class, m.execute(e, T.WORK, v));
            assertSame(v, recovered.get());
            assertEquals(S.FAILED, e.state);
        }
    }

    @Test void directCallToTheRecoveringEdgeDoesNotInheritAPastFailuresInput() {
        AtomicReference<Object> seen = new AtomicReference<>();
        StateMachine<Entity, S, T> m = machine((GuardContext<Entity, S, T> ctx) -> GuardResult.pass(),
                (TransitionContext<Entity, S, T> ctx) -> ActionOutcome.failed("declined"),
                (TransitionContext<Entity, S, T> ctx) -> {
                    seen.set(ctx.input().orElse(null));
                    return ActionOutcome.ok();
                });
        Verdict fromAFailure = new Verdict(true);
        m.execute(new Entity(), T.WORK, fromAFailure);
        assertSame(fromAFailure, seen.get());
        Verdict direct = new Verdict(false);
        m.execute(new Entity(), T.RECOVER, direct);
        assertSame(direct, seen.get());
    }

    @Test void aWrongGuardInputTypeAbortsWithoutCommit() {
        StateMachine<Entity, S, T> m = machine(
                (GuardContext<Entity, S, T> ctx) -> { ctx.input(String.class); return GuardResult.pass(); },
                (TransitionContext<Entity, S, T> ctx) -> ActionOutcome.ok(),
                (TransitionContext<Entity, S, T> ctx) -> ActionOutcome.ok());
        Entity e = new Entity();
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                () -> m.execute(e, T.WORK, new Verdict(true)));
        assertInstanceOf(ClassCastException.class, error.getCause());
        assertEquals(S.OPEN, e.state);
    }

    @Test void aCascadeFailureRouteAlsoHasNoRootInput() {
        Entity root = new Entity();
        Entity child = new Entity();
        Object value = new Object();
        AtomicInteger recoveries = new AtomicInteger();
        StateMachine<Entity, S, T> m = machine((GuardContext<Entity, S, T> ctx) -> GuardResult.pass(),
                (TransitionContext<Entity, S, T> ctx) -> {
                    if (ctx.depth() == 0) {
                        assertSame(value, ctx.input().orElseThrow());
                        assertInstanceOf(Outcome.Failed.class, ctx.cascade(KEY, child, T.WORK));
                        assertSame(value, ctx.input().orElseThrow());
                        return ActionOutcome.ok();
                    }
                    assertTrue(ctx.input().isEmpty());
                    return ActionOutcome.failed("child failed");
                },
                (TransitionContext<Entity, S, T> ctx) -> {
                    assertTrue(ctx.input().isEmpty());
                    assertEquals(1, ctx.depth());
                    recoveries.incrementAndGet();
                    return ActionOutcome.ok();
                });
        assertInstanceOf(Outcome.Applied.class, m.execute(root, T.WORK, value));
        assertEquals(S.DONE, root.state);
        assertEquals(S.FAILED, child.state);
        assertEquals(1, recoveries.get());
    }

    /**
     * A watch reaction opens a root of its own: the call that moves the peer passes an input, and the transition the
     * owner's rule then selects runs without it.
     */
    @Test void watchReevaluationRunsItsTransitionWithoutTheCallersInput() {
        Entity owner = new Entity();
        Entity peer = new Entity();
        Verdict callers = new Verdict(true);
        List<Optional<?>> seen = new ArrayList<>();
        GraphDefinition<Entity, S, T> watched = StateGraph.define(KEY).initial(S.OPEN).terminal(S.DONE)
                .terminal(S.FAILED)
                .statePolicy(S.OPEN, "closed")
                .transition(T.WORK, S.OPEN, S.DONE)
                    .action("work", (TransitionContext<Entity, S, T> ctx) -> {
                        seen.add(ctx.input());
                        return ActionOutcome.ok();
                    })
                    .endTransition()
                .transition(T.WATCH_IT, S.OPEN, S.FAILED)
                    .action("watched", (TransitionContext<Entity, S, T> ctx) -> {
                        seen.add(ctx.input());
                        return ActionOutcome.ok();
                    })
                    .endTransition()
                .watch(S.OPEN, KEY, (Entity e) -> List.of(peer),
                        (Entity moved) -> moved == peer ? Optional.of(owner) : Optional.<Entity>empty())
                    .when("always", (Entity e, Collection<? extends Entity> peers,
                            PeerView<Entity, S, T> view) -> true).execute(T.WATCH_IT)
                    .endWatch()
                .build();
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(watched).state((Entity e) -> e.state, (Entity e, S s) -> e.state = s)
                        .identity(System::identityHashCode)).build()) {
            Outcome.Applied<?, ?, ?> applied = assertInstanceOf(Outcome.Applied.class,
                    registry.of(KEY).execute(peer, T.WORK, callers));
            Reaction.Done reaction = assertInstanceOf(Reaction.Done.class, applied.reactions().getFirst());
            assertInstanceOf(Outcome.Applied.class, reaction.outcome());
            assertEquals(List.of(Optional.of(callers), Optional.empty()), seen);
            assertEquals(S.DONE, peer.state);
            assertEquals(S.FAILED, owner.state);
        }
    }

    @Test void valueGettersRemainReadableAfterReturnAndOnAnotherThread() throws Exception {
        AtomicReference<GuardContext<Entity, S, T>> guard = new AtomicReference<>();
        AtomicReference<TransitionContext<Entity, S, T>> action = new AtomicReference<>();
        StateMachine<Entity, S, T> m = machine(
                (GuardContext<Entity, S, T> ctx) -> { guard.set(ctx); return GuardResult.pass(); },
                (TransitionContext<Entity, S, T> ctx) -> { action.set(ctx); return ActionOutcome.ok(); },
                (TransitionContext<Entity, S, T> ctx) -> ActionOutcome.ok());
        Verdict v = new Verdict(true);
        m.execute(new Entity(), T.WORK, v);
        assertSame(v, guard.get().input().orElseThrow());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            assertSame(v,
                    executor.submit(() -> action.get().input(Verdict.class).orElseThrow()).get(5, TimeUnit.SECONDS));
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
        GraphDefinition<Entity, S, T> graph = StateGraph.define(KEY).initial(S.OPEN).terminal(S.DONE).terminal(S.FAILED)
                .statePolicy(S.OPEN, "closed")
                .transition(T.WORK, S.OPEN, S.DONE)
                    .asyncAction("work", (TransitionContext<Entity, S, T> ctx) -> {
                        assertSame(expected.get(ctx.entity()), ctx.input(Verdict.class).orElseThrow());
                        return CompletableFuture.completedFuture(ActionOutcome.ok());
                    })
                    .asyncGuard("eligible", (GuardContext<Entity, S, T> ctx) -> {
                        Verdict value = ctx.input(Verdict.class).orElseThrow();
                        assertSame(expected.get(ctx.entity()), value);
                        bothEntered.countDown();
                        return release.thenApply((Void ignored) -> GuardResult.pass());
                    }).endTransition()
                .transition(T.RECOVER, S.OPEN, S.FAILED).endTransition()
                .build();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            StateMachine<Entity, S, T> m = StateMachines.from(graph)
                    .state((Entity e) -> e.state, (Entity e, S s) -> e.state = s)
                    .asyncTimeout(Duration.ofSeconds(10)).asyncExecutor(executor).build();
            CompletableFuture<Outcome<Entity, S, T>> one = m.async().execute(first, T.WORK, a);
            CompletableFuture<Outcome<Entity, S, T>> two = m.async().execute(second, T.WORK, b);
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
