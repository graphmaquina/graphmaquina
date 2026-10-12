package consumer;

import static io.github.graphmaquina.test.GraphAssertions.assertThat;
import static io.github.graphmaquina.test.fixtures.TestDomain.KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.runtime.MachineRegistry;
import io.github.graphmaquina.test.AsyncAssertions;
import io.github.graphmaquina.test.fixtures.TestDomain;
import io.github.graphmaquina.test.fixtures.TestDomain.Entity;
import io.github.graphmaquina.test.fixtures.TestDomain.Id;
import io.github.graphmaquina.test.fixtures.TestDomain.State;
import io.github.graphmaquina.test.probe.ActionCall;
import io.github.graphmaquina.test.probe.ActionProbe;
import io.github.graphmaquina.test.probe.CallTrace;
import io.github.graphmaquina.test.probe.ControlledAsyncAction;
import io.github.graphmaquina.test.probe.ControlledAsyncGuard;
import io.github.graphmaquina.test.probe.GuardCall;
import io.github.graphmaquina.test.probe.GuardProbe;
import io.github.graphmaquina.test.probe.PendingInvocation;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * The per-invocation input a probe records, which belongs to the call and not to the graph: it survives the
 * transaction, it is never inherited by a cascade, a recovery route sees the input of the invocation that
 * failed, and concurrent asynchronous calls keep theirs apart. Every call also records the identifier that
 * selected its edge, which is what the transition-identity contract adds without taking the payload away.
 */
class ProbeInputTest {

    private static final Duration BUDGET = Duration.ofSeconds(5);

    @Test
    void syncHistoryAndTraceRetainEachInputAfterTheRegistryCloses() {
        CallTrace trace = CallTrace.create();
        GuardProbe<Entity, State, Id> guard =
                GuardProbe.<Entity, State, Id>create("gate", trace).otherwiseReturn(GuardResult.pass());
        ActionProbe<Entity, State, Id> action =
                ActionProbe.<Entity, State, Id>create("prepare", trace).otherwiseReturn(ActionOutcome.ok());
        Object first = new Object();
        Object second = new Object();
        try (MachineRegistry registry = TestDomain.registry(graph(guard, action))) {
            registry.of(KEY).execute(new Entity("first", State.NEW), Id.PREPARE, first);
            registry.of(KEY).execute(new Entity("second", State.NEW), Id.PREPARE, second);
            registry.of(KEY).execute(new Entity("empty", State.NEW), Id.PREPARE);
        }
        assertSame(first, guard.calls().snapshot().get(0).input(Object.class).orElseThrow());
        assertSame(second, action.calls().snapshot().get(1).input().orElseThrow());
        for (int i = 0; i < 3; i++) {
            assertSame(guard.calls().snapshot().get(i), trace.events().get(i * 2).call());
            assertSame(action.calls().snapshot().get(i), trace.events().get(i * 2 + 1).call());
            assertEquals(Id.PREPARE, guard.calls().snapshot().get(i).transition());
            assertEquals(Id.PREPARE, action.calls().snapshot().get(i).transition());
        }
        assertThat(action.calls().snapshot().get(0)).hasInputSameAs(first);
        assertThat(guard.calls().snapshot().get(1)).hasInputSameAs(second);
        assertThat(guard.calls().snapshot().get(2)).hasNoInput();
        assertThat(action.calls().snapshot().get(2)).hasNoInput();
    }

    @Test
    void cascadedCallbacksRecordEmptyInputWhileRootKeepsItsArgument() {
        CallTrace trace = CallTrace.create();
        Entity root = new Entity("root", State.NEW);
        Entity child = new Entity("child", State.NEW);
        Object input = new Object();
        GuardProbe<Entity, State, Id> guard =
                GuardProbe.<Entity, State, Id>create("gate", trace).otherwiseReturn(GuardResult.pass());
        ActionProbe<Entity, State, Id> action = ActionProbe.<Entity, State, Id>create("prepare", trace)
                .otherwiseReturn(ActionOutcome.ok())
                .onCall(ctx -> {
                    if (ctx.entity() == root) {
                        ctx.cascade(KEY, child, Id.PREPARE);
                    }
                });
        try (MachineRegistry registry = TestDomain.registry(graph(guard, action))) {
            assertThat(registry.of(KEY).execute(root, Id.PREPARE, input)).isApplied();
        }
        assertThat(guard.calls().snapshot().get(0)).hasInputSameAs(input);
        assertThat(action.calls().snapshot().get(0)).hasInputSameAs(input);
        assertThat(guard.calls().snapshot().get(1)).hasNoInput();
        assertThat(action.calls().snapshot().get(1)).hasNoInput();
        assertEquals(1, action.calls().snapshot().get(1).depth());
        assertEquals(State.READY, child.state());
    }

    @Test
    void failureActionKeepsInputForFailedResultsAndConfiguredExceptions() {
        for (boolean throwing : new boolean[] {false, true}) {
            CallTrace trace = CallTrace.create();
            ActionProbe<Entity, State, Id> work = ActionProbe.create("work", trace);
            if (throwing) {
                work.thenThrow(new IllegalStateException("unavailable"));
            } else {
                work.thenReturn(ActionOutcome.failed("unavailable"));
            }
            ActionProbe<Entity, State, Id> recovery =
                    ActionProbe.<Entity, State, Id>create("recover", trace).thenReturn(ActionOutcome.ok());
            GraphDefinition<Entity, State, Id> definition = StateGraph.define(KEY).initial(State.NEW)
                    .terminal(State.DONE).terminal(State.FAILED)
                    .statePolicy(State.NEW, "x").statePolicy(State.READY, "x")
                    .transition(Id.PREPARE, State.NEW, State.READY).action("work", work)
                        .onFailure(Id.RECOVER).failsOn(IllegalStateException.class).endTransition()
                    .transition(Id.RECOVER, State.NEW, State.FAILED).action("recover", recovery)
                        .endTransition()
                    .transition(Id.FINISH, State.READY, State.DONE).endTransition()
                    .build();
            Object input = new Object();
            Entity entity = new Entity("e", State.NEW);
            try (MachineRegistry registry = TestDomain.registry(definition)) {
                registry.of(KEY).execute(entity, Id.PREPARE, input);
            }
            assertEquals(State.FAILED, entity.state());
            assertThat(work.calls().single()).hasInputSameAs(input);
            assertThat(recovery.calls().single()).hasInputSameAs(input);
            assertEquals(Id.PREPARE, work.calls().single().transition());
            assertEquals(Id.RECOVER, recovery.calls().single().transition());
        }
    }

    @Test
    void controlledAsyncCallsKeepInputsSeparateUntilAndAfterCompletion() {
        ControlledAsyncGuard<Entity, State, Id> guard = ControlledAsyncGuard.create();
        ControlledAsyncAction<Entity, State, Id> action = ControlledAsyncAction.create();
        GraphDefinition<Entity, State, Id> graph = StateGraph.define(KEY).initial(State.NEW)
                .terminal(State.DONE).terminal(State.FAILED)
                .statePolicy(State.NEW, "x").statePolicy(State.READY, "x")
                .transition(Id.PREPARE, State.NEW, State.READY)
                    .asyncAction("prepare", action).asyncGuard("gate", guard).timeout(BUDGET)
                    .endTransition()
                .transition(Id.RECOVER, State.NEW, State.FAILED).endTransition()
                .transition(Id.FINISH, State.READY, State.DONE).endTransition()
                .build();
        MachineRegistry registry = TestDomain.registry(graph);
        Object first = new Object();
        Object second = new Object();
        try (registry; guard; action) {
            CompletableFuture<Outcome<Entity, State, Id>> one = registry.of(KEY).async()
                    .execute(new Entity("one", State.NEW), Id.PREPARE, first);
            PendingInvocation<GuardCall<Entity, State, Id>, GuardResult> g1 = guard.awaitInvocation(BUDGET);
            CompletableFuture<Outcome<Entity, State, Id>> two = registry.of(KEY).async()
                    .execute(new Entity("two", State.NEW), Id.PREPARE, second);
            PendingInvocation<GuardCall<Entity, State, Id>, GuardResult> g2 = guard.awaitInvocation(BUDGET);
            assertThat(g1.call()).hasInputSameAs(first);
            assertThat(g2.call()).hasInputSameAs(second);
            g2.complete(GuardResult.pass());
            PendingInvocation<ActionCall<Entity, State, Id>, ActionOutcome> a2 = action.awaitInvocation(BUDGET);
            g1.complete(GuardResult.pass());
            PendingInvocation<ActionCall<Entity, State, Id>, ActionOutcome> a1 = action.awaitInvocation(BUDGET);
            assertThat(a1.call()).hasInputSameAs(first);
            assertThat(a2.call()).hasInputSameAs(second);
            assertEquals(Id.PREPARE, a1.call().transition());
            a1.complete(ActionOutcome.ok());
            a2.complete(ActionOutcome.ok());
            AsyncAssertions.await(one, BUDGET).isApplied();
            AsyncAssertions.await(two, BUDGET).isApplied();
            CompletableFuture<Outcome<Entity, State, Id>> empty = registry.of(KEY).async()
                    .execute(new Entity("empty", State.NEW), Id.PREPARE);
            PendingInvocation<GuardCall<Entity, State, Id>, GuardResult> g3 = guard.awaitInvocation(BUDGET);
            assertThat(g3.call()).hasNoInput();
            g3.complete(GuardResult.pass());
            PendingInvocation<ActionCall<Entity, State, Id>, ActionOutcome> a3 = action.awaitInvocation(BUDGET);
            assertThat(a3.call()).hasNoInput();
            a3.complete(ActionOutcome.ok());
            AsyncAssertions.await(empty, BUDGET).isApplied();
        }
        assertSame(first, guard.invocations().get(0).call().input(Object.class).orElseThrow());
        assertSame(second, action.invocations().get(0).call().input(Object.class).orElseThrow());
    }

    @Test
    void theShortConstructorsMeanNoInputAndTypedAccessorsFollowContextRules() {
        Entity entity = new Entity("e", State.NEW);
        GuardCall<Entity, State, Id> emptyGuard =
                new GuardCall<>(entity, State.NEW, Id.PREPARE, State.READY);
        ActionCall<Entity, State, Id> emptyAction =
                new ActionCall<>(entity, State.NEW, Id.PREPARE, State.READY, 0);
        assertTrue(emptyGuard.input(CharSequence.class).isEmpty());
        assertTrue(emptyAction.input(CharSequence.class).isEmpty());
        assertThrows(NullPointerException.class, () -> emptyGuard.input(null));
        assertThrows(NullPointerException.class, () -> emptyAction.input(null));
        assertThrows(IllegalArgumentException.class, () -> emptyGuard.input(int.class));
        assertThrows(IllegalArgumentException.class, () -> emptyAction.input(void.class));
        String input = new String("verdict");
        GuardCall<Entity, State, Id> guard =
                new GuardCall<>(entity, State.NEW, Id.PREPARE, State.READY, Optional.of(input));
        ActionCall<Entity, State, Id> action =
                new ActionCall<>(entity, State.NEW, Id.PREPARE, State.READY, 0, Optional.of(input));
        assertSame(input, guard.input(CharSequence.class).orElseThrow());
        assertSame(input, action.input(Object.class).orElseThrow());
        assertThrows(ClassCastException.class, () -> guard.input(Integer.class));
        assertThrows(ClassCastException.class, () -> action.input(Integer.class));
        assertThrows(NullPointerException.class,
                () -> new GuardCall<>(entity, State.NEW, Id.PREPARE, State.READY, null));
        assertThrows(NullPointerException.class,
                () -> new ActionCall<>(entity, State.NEW, Id.PREPARE, State.READY, 0, null));
        assertThrows(NullPointerException.class,
                () -> new GuardCall<>(entity, State.NEW, null, State.READY, Optional.empty()));
        assertThrows(NullPointerException.class,
                () -> new ActionCall<>(entity, State.NEW, null, State.READY, 0, Optional.empty()));
    }

    private static GraphDefinition<Entity, State, Id> graph(GuardProbe<Entity, State, Id> guard,
            ActionProbe<Entity, State, Id> action) {
        return StateGraph.define(KEY).initial(State.NEW)
                .terminal(State.DONE).terminal(State.FAILED)
                .statePolicy(State.NEW, "x").statePolicy(State.READY, "x")
                .transition(Id.PREPARE, State.NEW, State.READY)
                    .action("prepare", action).guard("gate", guard).endTransition()
                .transition(Id.RECOVER, State.NEW, State.FAILED).endTransition()
                .transition(Id.FINISH, State.READY, State.DONE).endTransition()
                .build();
    }
}
