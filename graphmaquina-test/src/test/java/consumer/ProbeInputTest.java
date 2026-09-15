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
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.test.AsyncAssertions;
import io.github.graphmaquina.test.fixtures.TestDomain;
import io.github.graphmaquina.test.fixtures.TestDomain.Entity;
import io.github.graphmaquina.test.fixtures.TestDomain.State;
import io.github.graphmaquina.test.probe.ActionCall;
import io.github.graphmaquina.test.probe.ActionProbe;
import io.github.graphmaquina.test.probe.CallTrace;
import io.github.graphmaquina.test.probe.ControlledAsyncAction;
import io.github.graphmaquina.test.probe.ControlledAsyncGuard;
import io.github.graphmaquina.test.probe.GuardCall;
import io.github.graphmaquina.test.probe.GuardProbe;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProbeInputTest {
    private static final Duration BUDGET = Duration.ofSeconds(5);

    @Test
    void syncHistoryAndTraceRetainEachInputAfterTheRegistryCloses() {
        CallTrace trace = CallTrace.create();
        var guard = GuardProbe.<Entity, State>create("gate", trace).otherwiseReturn(GuardResult.pass());
        var action = ActionProbe.<Entity, State>create("prepare", trace).otherwiseReturn(ActionOutcome.ok());
        Object first = new Object();
        Object second = new Object();
        try (var registry = TestDomain.registry(graph(guard, action))) {
            registry.of(KEY).transitionTo(new Entity("first", State.NEW), State.READY, first);
            registry.of(KEY).transitionTo(new Entity("second", State.NEW), State.READY, second);
            registry.of(KEY).transitionTo(new Entity("empty", State.NEW), State.READY);
            registry.of(KEY).transitionTo(new Entity("null", State.NEW), State.READY, null);
        }
        assertSame(first, guard.calls().snapshot().get(0).input(Object.class).orElseThrow());
        assertSame(second, action.calls().snapshot().get(1).input().orElseThrow());
        for (int i = 0; i < 4; i++) {
            assertSame(guard.calls().snapshot().get(i), trace.events().get(i * 2).call());
            assertSame(action.calls().snapshot().get(i), trace.events().get(i * 2 + 1).call());
        }
        assertThat(action.calls().snapshot().get(0)).hasInputSameAs(first);
        assertThat(guard.calls().snapshot().get(1)).hasInputSameAs(second);
        for (int i : new int[] {2, 3}) {
            assertThat(guard.calls().snapshot().get(i)).hasNoInput();
            assertThat(action.calls().snapshot().get(i)).hasNoInput();
        }
    }

    @Test
    void cascadedCallbacksRecordEmptyInputWhileRootKeepsItsArgument() {
        CallTrace trace = CallTrace.create();
        Entity root = new Entity("root", State.NEW);
        Entity child = new Entity("child", State.NEW);
        Object input = new Object();
        var guard = GuardProbe.<Entity, State>create("gate", trace).otherwiseReturn(GuardResult.pass());
        var action = ActionProbe.<Entity, State>create("prepare", trace).otherwiseReturn(ActionOutcome.ok())
                .onCall(ctx -> { if (ctx.entity() == root) { ctx.cascade(KEY, child, State.READY); } });
        try (var registry = TestDomain.registry(graph(guard, action))) {
            assertThat(registry.of(KEY).transitionTo(root, State.READY, input)).isApplied();
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
            var work = ActionProbe.<Entity, State>create("work", trace);
            if (throwing) {
                work.thenThrow(new IllegalStateException("unavailable"));
            } else {
                work.thenReturn(ActionOutcome.failed("unavailable"));
            }
            var recovery = ActionProbe.<Entity, State>create("recover", trace).thenReturn(ActionOutcome.ok());
            var definition = StateGraph.define(KEY).initial(State.NEW)
                    .terminal(State.READY).terminal(State.DONE).terminal(State.FAILED)
                    .from(State.NEW).to(State.READY).action("work", work)
                        .onFailure(State.FAILED).failsOn(IllegalStateException.class)
                        .to(State.FAILED).action("recover", recovery).to(State.DONE).move().stay().forbidOthers("x")
                    .from(State.READY).stay().forbidOthers("x")
                    .from(State.DONE).stay().forbidOthers("x")
                    .from(State.FAILED).stay().forbidOthers("x").build();
            Object input = new Object();
            Entity entity = new Entity("e", State.NEW);
            try (var registry = TestDomain.registry(definition)) {
                registry.of(KEY).transitionTo(entity, State.READY, input);
            }
            assertEquals(State.FAILED, entity.state());
            assertThat(work.calls().single()).hasInputSameAs(input);
            assertThat(recovery.calls().single()).hasInputSameAs(input);
        }
    }

    @Test
    void controlledAsyncCallsKeepInputsSeparateUntilAndAfterCompletion() {
        var guard = ControlledAsyncGuard.<Entity, State>create();
        var action = ControlledAsyncAction.<Entity, State>create();
        var graph = StateGraph.define(KEY).initial(State.NEW)
                .terminal(State.READY).terminal(State.DONE).terminal(State.FAILED)
                .from(State.NEW).to(State.READY).asyncAction("prepare", action).asyncGuard("gate", guard)
                    .timeout(BUDGET).to(State.DONE).move().to(State.FAILED).move().stay().forbidOthers("x")
                .from(State.READY).stay().forbidOthers("x")
                .from(State.DONE).stay().forbidOthers("x")
                .from(State.FAILED).stay().forbidOthers("x").build();
        var registry = TestDomain.registry(graph);
        Object first = new Object();
        Object second = new Object();
        try (registry; guard; action) {
            var one = registry.of(KEY).async().transitionTo(new Entity("one", State.NEW), State.READY, first);
            var g1 = guard.awaitInvocation(BUDGET);
            var two = registry.of(KEY).async().transitionTo(new Entity("two", State.NEW), State.READY, second);
            var g2 = guard.awaitInvocation(BUDGET);
            assertThat(g1.call()).hasInputSameAs(first);
            assertThat(g2.call()).hasInputSameAs(second);
            g2.complete(GuardResult.pass());
            var a2 = action.awaitInvocation(BUDGET);
            g1.complete(GuardResult.pass());
            var a1 = action.awaitInvocation(BUDGET);
            assertThat(a1.call()).hasInputSameAs(first);
            assertThat(a2.call()).hasInputSameAs(second);
            a1.complete(ActionOutcome.ok());
            a2.complete(ActionOutcome.ok());
            AsyncAssertions.await(one, BUDGET).isApplied();
            AsyncAssertions.await(two, BUDGET).isApplied();
            var empty = registry.of(KEY).async().transitionTo(new Entity("empty", State.NEW), State.READY);
            var g3 = guard.awaitInvocation(BUDGET);
            assertThat(g3.call()).hasNoInput();
            g3.complete(GuardResult.pass());
            var a3 = action.awaitInvocation(BUDGET);
            assertThat(a3.call()).hasNoInput();
            a3.complete(ActionOutcome.ok());
            AsyncAssertions.await(empty, BUDGET).isApplied();
        }
        assertSame(first, guard.invocations().get(0).call().input(Object.class).orElseThrow());
        assertSame(second, action.invocations().get(0).call().input(Object.class).orElseThrow());
    }

    @Test
    void oldConstructorsMeanNoInputAndTypedAccessorsFollowContextRules() {
        Entity entity = new Entity("e", State.NEW);
        var emptyGuard = new GuardCall<>(entity, State.NEW, State.READY);
        var emptyAction = new ActionCall<>(entity, State.NEW, State.READY, 0);
        assertTrue(emptyGuard.input(CharSequence.class).isEmpty());
        assertTrue(emptyAction.input(CharSequence.class).isEmpty());
        assertThrows(NullPointerException.class, () -> emptyGuard.input(null));
        assertThrows(NullPointerException.class, () -> emptyAction.input(null));
        assertThrows(IllegalArgumentException.class, () -> emptyGuard.input(int.class));
        assertThrows(IllegalArgumentException.class, () -> emptyAction.input(void.class));
        String input = new String("verdict");
        var guard = new GuardCall<>(entity, State.NEW, State.READY, Optional.of(input));
        var action = new ActionCall<>(entity, State.NEW, State.READY, 0, Optional.of(input));
        assertSame(input, guard.input(CharSequence.class).orElseThrow());
        assertSame(input, action.input(Object.class).orElseThrow());
        assertThrows(ClassCastException.class, () -> guard.input(Integer.class));
        assertThrows(ClassCastException.class, () -> action.input(Integer.class));
        assertThrows(NullPointerException.class, () -> new GuardCall<>(entity, State.NEW, State.READY, null));
        assertThrows(NullPointerException.class, () -> new ActionCall<>(entity, State.NEW, State.READY, 0, null));
    }

    private static GraphDefinition<Entity, State> graph(GuardProbe<Entity, State> guard,
            ActionProbe<Entity, State> action) {
        return StateGraph.define(KEY).initial(State.NEW)
                .terminal(State.READY).terminal(State.DONE).terminal(State.FAILED)
                .from(State.NEW).to(State.READY).action("prepare", action).guard("gate", guard)
                    .to(State.DONE).move().to(State.FAILED).move().stay().forbidOthers("x")
                .from(State.READY).stay().forbidOthers("x")
                .from(State.DONE).stay().forbidOthers("x")
                .from(State.FAILED).stay().forbidOthers("x").build();
    }
}
