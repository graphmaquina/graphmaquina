package consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.TransitionGuard;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.error.StaleContextException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.runtime.MachineRegistry;
import io.github.graphmaquina.test.GraphAssertionError;
import io.github.graphmaquina.test.fixtures.TestDomain;
import io.github.graphmaquina.test.fixtures.TestDomain.Entity;
import io.github.graphmaquina.test.fixtures.TestDomain.State;
import io.github.graphmaquina.test.probe.ActionCall;
import io.github.graphmaquina.test.probe.ActionProbe;
import io.github.graphmaquina.test.probe.CallTrace;
import io.github.graphmaquina.test.probe.GuardCall;
import io.github.graphmaquina.test.probe.GuardProbe;
import io.github.graphmaquina.test.probe.HookProbe;
import io.github.graphmaquina.test.probe.TraceEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Contract of the scripted probes. A probe records the values it read on entry — never the live context — and
 * answers from an explicit script, so a test that forgot to program a response is told which probe and which
 * call ran out rather than seeing a machine misbehave. The shared {@link CallTrace} is the order in which the
 * callbacks were entered and nothing more: it does not certify that a response succeeded or that the hop
 * committed. Every mismatch is read off the fields of the {@link GraphAssertionError}, not off its type alone.
 */
class ProbeTest {

    // Two distinct instances: the DSL rejects the same guard object declared twice on one cell (SG105).
    private static final TransitionGuard<Entity, State> PASS_FIRST = context -> GuardResult.pass();
    private static final TransitionGuard<Entity, State> PASS_SECOND = context -> GuardResult.pass();
    private static final Consumer<Entity> NO_HOOK = entity -> { };

    // --- the premise of this task: the engine's short-circuit is visible in the probes -----------------

    @Test
    void rejectedGuardPreventsLaterGuardAndActionCalls() {
        CallTrace trace = CallTrace.create();
        GuardProbe<Entity, State> first = GuardProbe.<Entity, State>create("first", trace)
                .thenReturn(GuardResult.reject("no title"));
        GuardProbe<Entity, State> second = GuardProbe.<Entity, State>create("second", trace)
                .otherwiseReturn(GuardResult.pass());
        ActionProbe<Entity, State> action = ActionProbe.<Entity, State>create("prepare", trace)
                .otherwiseReturn(ActionOutcome.ok());
        GraphDefinition<Entity, State> graph = StateGraph.define(TestDomain.KEY).initial(State.NEW)
                .terminal(State.READY).terminal(State.DONE).terminal(State.FAILED)
                .from(State.NEW).to(State.READY).action("prepare", action)
                        .guard("first", first).guard("second", second)
                    .to(State.DONE).move().to(State.FAILED).move().stay().forbidOthers("x")
                .from(State.READY).stay().forbidOthers("x")
                .from(State.DONE).stay().forbidOthers("x")
                .from(State.FAILED).stay().forbidOthers("x").build();
        try (MachineRegistry registry = TestDomain.registry(graph)) {
            registry.of(TestDomain.KEY).transitionTo(new Entity("e", State.NEW), State.READY);
        }
        trace.assertNamesExactly("first");
        second.calls().assertCount(0);
        action.calls().assertCount(0);
    }

    @Test
    void guardsThenTheActionThenTheHookShareOneOrder() {
        CallTrace trace = CallTrace.create();
        GuardProbe<Entity, State> first = GuardProbe.<Entity, State>create("first", trace)
                .otherwiseReturn(GuardResult.pass());
        GuardProbe<Entity, State> second = GuardProbe.<Entity, State>create("second", trace)
                .otherwiseReturn(GuardResult.pass());
        ActionProbe<Entity, State> action = ActionProbe.<Entity, State>create("prepare", trace)
                .otherwiseReturn(ActionOutcome.ok());
        HookProbe<Entity> hook = HookProbe.<Entity>create("audit", trace).otherwiseSucceed();
        Entity entity = new Entity("e", State.NEW);
        transition(graph(first, second, action, hook), entity);
        trace.assertNamesExactly("first", "second", "prepare", "audit");
        assertEquals(State.READY, entity.state());
        first.calls().assertCount(1);
        hook.calls().assertCount(1);
    }

    @Test
    void theTraceRecordsEntryIntoACallbackAndNeitherSuccessNorCommit() {
        CallTrace trace = CallTrace.create();
        GuardProbe<Entity, State> first = GuardProbe.<Entity, State>create("first", trace)
                .otherwiseReturn(GuardResult.pass());
        IllegalStateException boom = new IllegalStateException("no stock");
        ActionProbe<Entity, State> action = ActionProbe.<Entity, State>create("prepare", trace).thenThrow(boom);
        HookProbe<Entity> hook = HookProbe.<Entity>create("audit", trace).otherwiseSucceed();
        Entity entity = new Entity("e", State.NEW);
        TransitionAbortedException aborted = assertThrows(TransitionAbortedException.class,
                () -> transition(graph(first, PASS_SECOND, action, hook), entity));
        assertSame(boom, aborted.getCause());
        trace.assertNamesExactly("first", "prepare");
        hook.calls().assertCount(0);
        assertEquals(State.NEW, entity.state());
    }

    // --- scripting ------------------------------------------------------------------------------------

    @Test
    void queuedResponsesAreServedInTheOrderTheyWereScripted() {
        CallTrace trace = CallTrace.create();
        ActionProbe<Entity, State> action = ActionProbe.<Entity, State>create("prepare", trace)
                .thenReturn(ActionOutcome.ok())
                .thenReturn(ActionOutcome.failed("no stock"));
        HookProbe<Entity> hook = HookProbe.<Entity>create("audit", trace).otherwiseSucceed();
        GraphDefinition<Entity, State> graph = graph(PASS_FIRST, PASS_SECOND, action, hook);
        Entity moved = new Entity("moved", State.NEW);
        Entity failed = new Entity("failed", State.NEW);
        try (MachineRegistry registry = TestDomain.registry(graph)) {
            registry.of(TestDomain.KEY).transitionTo(moved, State.READY);
            registry.of(TestDomain.KEY).transitionTo(failed, State.READY);
        }
        assertEquals(State.READY, moved.state());
        assertEquals(State.FAILED, failed.state());
        assertEquals(List.of(moved, failed),
                action.calls().snapshot().stream().map(ActionCall::entity).toList());
        assertEquals(List.of(moved), hook.calls().snapshot());
        trace.assertNamesExactly("prepare", "audit", "prepare");
    }

    @Test
    void theDefaultAnswersEveryCallTheQueueNoLongerCovers() {
        CallTrace trace = CallTrace.create();
        ActionProbe<Entity, State> action = ActionProbe.<Entity, State>create("prepare", trace)
                .thenReturn(ActionOutcome.failed("first try"))
                .otherwiseReturn(ActionOutcome.ok());
        GraphDefinition<Entity, State> graph = graph(PASS_FIRST, PASS_SECOND, action, NO_HOOK);
        List<Entity> entities = List.of(new Entity("a", State.NEW), new Entity("b", State.NEW),
                new Entity("c", State.NEW));
        try (MachineRegistry registry = TestDomain.registry(graph)) {
            for (Entity entity : entities) {
                registry.of(TestDomain.KEY).transitionTo(entity, State.READY);
            }
        }
        assertEquals(List.of(State.FAILED, State.READY, State.READY),
                entities.stream().map(Entity::state).toList());
        action.calls().assertCount(3);
    }

    @Test
    void aCallWithNoScriptedResponseNamesTheProbeAndTheOrdinal() {
        HookProbe<Entity> hook = HookProbe.<Entity>create("audit", CallTrace.create()).thenSucceed();
        Entity entity = new Entity("e", State.READY);
        hook.accept(entity);
        GraphAssertionError error = assertThrows(GraphAssertionError.class, () -> hook.accept(entity));
        assertEquals("probe audit", error.context());
        assertEquals("scripted response for call 2", error.property());
        assertEquals("a scripted response or a default", error.expected());
        assertEquals("no response left", error.actual());
        hook.calls().assertCount(2);
    }

    @Test
    void anUnscriptedActionFailsTheTestRatherThanTheMachine() {
        CallTrace trace = CallTrace.create();
        ActionProbe<Entity, State> action = ActionProbe.create("prepare", trace);
        Entity entity = new Entity("e", State.NEW);
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> transition(graph(PASS_FIRST, PASS_SECOND, action, NO_HOOK), entity));
        assertEquals("probe prepare", error.context());
        assertEquals("scripted response for call 1", error.property());
        assertEquals(State.NEW, entity.state());
        trace.assertNamesExactly("prepare");
    }

    @Test
    void aThrownResponseIsThrownByIdentity() {
        IllegalStateException boom = new IllegalStateException("audit is down");
        HookProbe<Entity> hook = HookProbe.<Entity>create("audit", CallTrace.create()).thenThrow(boom);
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> hook.accept(new Entity("e", State.READY)));
        assertSame(boom, thrown);
    }

    @Test
    void aGuardThatThrowsAbortsWithTheVeryExceptionItWasGiven() {
        CallTrace trace = CallTrace.create();
        IllegalStateException boom = new IllegalStateException("cannot decide");
        GuardProbe<Entity, State> first = GuardProbe.<Entity, State>create("first", trace).thenThrow(boom);
        ActionProbe<Entity, State> action = ActionProbe.<Entity, State>create("prepare", trace)
                .otherwiseReturn(ActionOutcome.ok());
        TransitionAbortedException aborted = assertThrows(TransitionAbortedException.class,
                () -> transition(graph(first, PASS_SECOND, action, NO_HOOK), new Entity("e", State.NEW)));
        assertSame(boom, aborted.getCause());
        action.calls().assertCount(0);
        trace.assertNamesExactly("first");
    }

    @Test
    void theSideEffectRunsForAThrowingResponseButNotForAMissingOne() {
        IllegalStateException boom = new IllegalStateException("audit is down");
        List<Entity> seen = new ArrayList<>();
        HookProbe<Entity> hook = HookProbe.<Entity>create("audit", CallTrace.create())
                .thenThrow(boom).onCall(seen::add);
        Entity first = new Entity("first", State.READY);
        Entity second = new Entity("second", State.READY);
        assertSame(boom, assertThrows(IllegalStateException.class, () -> hook.accept(first)));
        assertThrows(GraphAssertionError.class, () -> hook.accept(second));
        assertEquals(List.of(first), seen);
        assertEquals(List.of(first, second), hook.calls().snapshot());
    }

    @Test
    void anActionSideEffectRunsForEveryAnsweredCallIncludingAThrowingOne() {
        CallTrace trace = CallTrace.create();
        IllegalStateException boom = new IllegalStateException("no stock");
        List<String> seen = new ArrayList<>();
        ActionProbe<Entity, State> action = ActionProbe.<Entity, State>create("prepare", trace)
                .thenReturn(ActionOutcome.ok())
                .thenThrow(boom)
                .onCall(context -> seen.add(context.entity().id() + ":" + context.to()));
        GraphDefinition<Entity, State> graph = graph(PASS_FIRST, PASS_SECOND, action, NO_HOOK);
        Entity applied = new Entity("applied", State.NEW);
        transition(graph, applied);
        assertEquals(State.READY, applied.state());
        TransitionAbortedException aborted = assertThrows(TransitionAbortedException.class,
                () -> transition(graph, new Entity("threw", State.NEW)));
        assertSame(boom, aborted.getCause());
        assertThrows(GraphAssertionError.class,
                () -> transition(graph, new Entity("unscripted", State.NEW)));
        assertEquals(List.of("applied:READY", "threw:READY"), seen);
        action.calls().assertCount(3);
    }

    @Test
    void aGuardSideEffectRunsForEveryAnsweredCallIncludingAThrowingOne() {
        CallTrace trace = CallTrace.create();
        IllegalStateException boom = new IllegalStateException("cannot decide");
        List<String> seen = new ArrayList<>();
        GuardProbe<Entity, State> first = GuardProbe.<Entity, State>create("first", trace)
                .thenReturn(GuardResult.pass())
                .thenThrow(boom)
                .onCall(context -> seen.add(context.entity().id() + ":" + context.from()));
        ActionProbe<Entity, State> action = ActionProbe.<Entity, State>create("prepare", trace)
                .otherwiseReturn(ActionOutcome.ok());
        GraphDefinition<Entity, State> graph = graph(first, PASS_SECOND, action, NO_HOOK);
        Entity passed = new Entity("passed", State.NEW);
        transition(graph, passed);
        assertEquals(State.READY, passed.state());
        TransitionAbortedException aborted = assertThrows(TransitionAbortedException.class,
                () -> transition(graph, new Entity("threw", State.NEW)));
        assertSame(boom, aborted.getCause());
        assertThrows(GraphAssertionError.class,
                () -> transition(graph, new Entity("unscripted", State.NEW)));
        assertEquals(List.of("passed:NEW", "threw:NEW"), seen);
        first.calls().assertCount(3);
        action.calls().assertCount(1);
    }

    // --- what a call records --------------------------------------------------------------------------

    @Test
    void anActionCallKeepsTheValuesReadOnEntryAndNotTheLiveContext() {
        CallTrace trace = CallTrace.create();
        AtomicReference<TransitionContext<Entity, State>> captured = new AtomicReference<>();
        ActionProbe<Entity, State> action = ActionProbe.<Entity, State>create("prepare", trace)
                .otherwiseReturn(ActionOutcome.ok())
                .onCall(captured::set);
        Entity entity = new Entity("e", State.NEW);
        transition(graph(PASS_FIRST, PASS_SECOND, action, NO_HOOK), entity);
        ActionCall<Entity, State> call = action.calls().single();
        assertSame(entity, call.entity());
        assertEquals(State.NEW, call.from());
        assertEquals(State.READY, call.to());
        assertEquals(0, call.depth());
        assertEquals(State.READY, entity.state());
        assertThrows(StaleContextException.class, () -> captured.get().peer(TestDomain.KEY));
    }

    @Test
    void aGuardCallKeepsTheEntityAndTheEdgeItWasAskedAbout() {
        CallTrace trace = CallTrace.create();
        GuardProbe<Entity, State> first = GuardProbe.<Entity, State>create("first", trace)
                .otherwiseReturn(GuardResult.pass());
        ActionProbe<Entity, State> action = ActionProbe.<Entity, State>create("prepare", trace)
                .otherwiseReturn(ActionOutcome.ok());
        Entity entity = new Entity("e", State.NEW);
        transition(graph(first, PASS_SECOND, action, NO_HOOK), entity);
        GuardCall<Entity, State> call = first.calls().single();
        assertSame(entity, call.entity());
        assertEquals(State.NEW, call.from());
        assertEquals(State.READY, call.to());
    }

    @Test
    void theTraceCarriesTheSameCallTheProbeRecorded() {
        CallTrace trace = CallTrace.create();
        GuardProbe<Entity, State> first = GuardProbe.<Entity, State>create("first", trace)
                .otherwiseReturn(GuardResult.pass());
        ActionProbe<Entity, State> action = ActionProbe.<Entity, State>create("prepare", trace)
                .otherwiseReturn(ActionOutcome.ok());
        HookProbe<Entity> hook = HookProbe.<Entity>create("audit", trace).otherwiseSucceed();
        Entity entity = new Entity("e", State.NEW);
        transition(graph(first, PASS_SECOND, action, hook), entity);
        List<TraceEvent> events = trace.events();
        assertEquals(List.of(0L, 1L, 2L), events.stream().map(TraceEvent::sequence).toList());
        assertEquals(List.of("first", "prepare", "audit"), events.stream().map(TraceEvent::name).toList());
        assertSame(first.calls().single(), events.get(0).call());
        assertSame(action.calls().single(), events.get(1).call());
        assertSame(entity, events.get(2).call());
    }

    // --- counters, snapshots and their failures -------------------------------------------------------

    @Test
    void snapshotsAreImmutableCopiesTakenWhenTheyWereAsked() {
        CallTrace trace = CallTrace.create();
        HookProbe<Entity> hook = HookProbe.<Entity>create("audit", trace).otherwiseSucceed();
        Entity first = new Entity("first", State.READY);
        Entity second = new Entity("second", State.READY);
        hook.accept(first);
        List<Entity> calls = hook.calls().snapshot();
        List<TraceEvent> events = trace.events();
        hook.accept(second);
        assertEquals(List.of(first), calls);
        assertEquals(1, events.size());
        assertEquals(List.of(first, second), hook.calls().snapshot());
        assertEquals(2, trace.events().size());
        assertThrows(UnsupportedOperationException.class, () -> calls.add(second));
        assertThrows(UnsupportedOperationException.class, events::clear);
    }

    @Test
    void assertCountReportsTheCountItSaw() {
        HookProbe<Entity> hook = HookProbe.<Entity>create("audit", CallTrace.create()).otherwiseSucceed();
        hook.accept(new Entity("e", State.READY));
        GraphAssertionError error =
                assertThrows(GraphAssertionError.class, () -> hook.calls().assertCount(3));
        assertEquals("probe audit calls", error.context());
        assertEquals("count", error.property());
        assertEquals(Integer.valueOf(3), error.expected());
        assertEquals(Integer.valueOf(1), error.actual());
        hook.calls().assertCount(1);
    }

    @Test
    void singleReturnsTheOnlyCallAndReportsEveryOtherCount() {
        HookProbe<Entity> hook = HookProbe.<Entity>create("audit", CallTrace.create()).otherwiseSucceed();
        GraphAssertionError empty = assertThrows(GraphAssertionError.class, () -> hook.calls().single());
        assertEquals("probe audit calls", empty.context());
        assertEquals("count", empty.property());
        assertEquals(Integer.valueOf(1), empty.expected());
        assertEquals(Integer.valueOf(0), empty.actual());
        Entity first = new Entity("first", State.READY);
        hook.accept(first);
        assertSame(first, hook.calls().single());
        hook.accept(new Entity("second", State.READY));
        GraphAssertionError many = assertThrows(GraphAssertionError.class, () -> hook.calls().single());
        assertEquals(Integer.valueOf(2), many.actual());
    }

    @Test
    void assertNamesExactlyReportsTheNamesItSaw() {
        CallTrace trace = CallTrace.create();
        HookProbe<Entity> audit = HookProbe.<Entity>create("audit", trace).otherwiseSucceed();
        HookProbe<Entity> notify = HookProbe.<Entity>create("notify", trace).otherwiseSucceed();
        Entity entity = new Entity("e", State.READY);
        audit.accept(entity);
        notify.accept(entity);
        audit.accept(entity);
        assertSame(trace, trace.assertNamesExactly("audit", "notify", "audit"));
        GraphAssertionError order = assertThrows(GraphAssertionError.class,
                () -> trace.assertNamesExactly("notify", "audit", "audit"));
        assertEquals("call trace", order.context());
        assertEquals("names", order.property());
        assertEquals(List.of("notify", "audit", "audit"), order.expected());
        assertEquals(List.of("audit", "notify", "audit"), order.actual());
        GraphAssertionError multiplicity = assertThrows(GraphAssertionError.class,
                () -> trace.assertNamesExactly("audit", "notify"));
        assertEquals(List.of("audit", "notify", "audit"), multiplicity.actual());
    }

    // --- fixture configuration errors -----------------------------------------------------------------

    @Test
    void aProbeRejectsAMissingOrBlankName() {
        CallTrace trace = CallTrace.create();
        assertThrows(NullPointerException.class, () -> ActionProbe.<Entity, State>create(null, trace));
        assertThrows(NullPointerException.class, () -> GuardProbe.<Entity, State>create("first", null));
        assertThrows(NullPointerException.class, () -> HookProbe.<Entity>create("audit", null));
        assertThrows(IllegalArgumentException.class, () -> ActionProbe.<Entity, State>create(" ", trace));
        assertThrows(IllegalArgumentException.class, () -> GuardProbe.<Entity, State>create("", trace));
        assertThrows(IllegalArgumentException.class, () -> HookProbe.<Entity>create(" ", trace));
    }

    @Test
    void aProbeRejectsANullResponseOrSideEffect() {
        CallTrace trace = CallTrace.create();
        ActionProbe<Entity, State> action = ActionProbe.create("prepare", trace);
        assertThrows(NullPointerException.class, () -> action.thenReturn(null));
        assertThrows(NullPointerException.class, () -> action.thenThrow(null));
        assertThrows(NullPointerException.class, () -> action.otherwiseReturn(null));
        assertThrows(NullPointerException.class, () -> action.onCall(null));
        GuardProbe<Entity, State> guard = GuardProbe.create("first", trace);
        assertThrows(NullPointerException.class, () -> guard.thenReturn(null));
        assertThrows(NullPointerException.class, () -> guard.otherwiseReturn(null));
        assertThrows(NullPointerException.class, () -> guard.onCall(null));
        HookProbe<Entity> hook = HookProbe.create("audit", trace);
        assertThrows(NullPointerException.class, () -> hook.thenThrow(null));
        assertThrows(NullPointerException.class, () -> hook.onCall(null));
    }

    // --- concurrency ----------------------------------------------------------------------------------

    @Test
    void concurrentCallsEachWithdrawTheirOwnResponse() throws Exception {
        int total = 2_000;
        CallTrace trace = CallTrace.create();
        HookProbe<Entity> hook = HookProbe.create("audit", trace);
        for (int i = 0; i < total; i++) {
            hook.thenSucceed();
        }
        Entity entity = new Entity("e", State.READY);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    hook.accept(entity);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        hook.calls().assertCount(total);
        assertEquals((long) total, trace.events().stream().map(TraceEvent::sequence).distinct().count());
        GraphAssertionError error = assertThrows(GraphAssertionError.class, () -> hook.accept(entity));
        assertEquals("scripted response for call " + (total + 1), error.property());
    }

    // --- fixtures -------------------------------------------------------------------------------------

    /**
     * NEW moves to READY through the action {@code prepare}, guarded by {@code first} and {@code second} and
     * followed by the hook {@code audit}; a failed outcome routes to FAILED, so the probe can script one
     * without aborting the transaction.
     */
    private static GraphDefinition<Entity, State> graph(TransitionGuard<Entity, State> first,
            TransitionGuard<Entity, State> second, TransitionAction<Entity, State> action,
            Consumer<? super Entity> hook) {
        return StateGraph.define(TestDomain.KEY).initial(State.NEW)
                .terminal(State.READY).terminal(State.DONE).terminal(State.FAILED)
                .from(State.NEW).to(State.READY).action("prepare", action)
                        .guard("first", first).guard("second", second)
                        .onFailure(State.FAILED).afterCommit("audit", hook)
                    .to(State.DONE).move().to(State.FAILED).move().stay().forbidOthers("x")
                .from(State.READY).stay().forbidOthers("x")
                .from(State.DONE).stay().forbidOthers("x")
                .from(State.FAILED).stay().forbidOthers("x").build();
    }

    private static void transition(GraphDefinition<Entity, State> graph, Entity entity) {
        try (MachineRegistry registry = TestDomain.registry(graph)) {
            registry.of(TestDomain.KEY).transitionTo(entity, State.READY);
        }
    }
}
