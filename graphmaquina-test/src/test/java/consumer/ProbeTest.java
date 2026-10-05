package consumer;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.TransitionGuard;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.error.AfterCommitException;
import io.github.graphmaquina.error.StaleContextException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.runtime.MachineRegistry;
import io.github.graphmaquina.test.GraphAssertionError;
import io.github.graphmaquina.test.fixtures.TestDomain;
import io.github.graphmaquina.test.fixtures.TestDomain.Entity;
import io.github.graphmaquina.test.fixtures.TestDomain.Id;
import io.github.graphmaquina.test.fixtures.TestDomain.State;
import io.github.graphmaquina.test.probe.ActionCall;
import io.github.graphmaquina.test.probe.ActionProbe;
import io.github.graphmaquina.test.probe.CallTrace;
import io.github.graphmaquina.test.probe.GuardCall;
import io.github.graphmaquina.test.probe.GuardProbe;
import io.github.graphmaquina.test.probe.HookProbe;
import io.github.graphmaquina.test.probe.ProbeCalls;
import io.github.graphmaquina.test.probe.TraceEvent;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;

/**
 * Contract of the scripted probes. A probe records the values it read on entry — never the live context — and
 * answers from an explicit script, so a test that forgot to program a response is told which probe and which
 * call ran out rather than seeing a machine misbehave. The shared {@link CallTrace} is the order in which the
 * callbacks were entered and nothing more: it does not certify that a response succeeded or that the hop
 * committed. Every mismatch is read off the fields of the {@link GraphAssertionError}, not off its type alone.
 */
class ProbeTest {

    // Two distinct instances: the DSL rejects the same guard object declared twice on one edge (SG105).
    private static final TransitionGuard<Entity, State, Id> PASS_FIRST = context -> GuardResult.pass();
    private static final TransitionGuard<Entity, State, Id> PASS_SECOND = context -> GuardResult.pass();
    private static final Consumer<Entity> NO_HOOK = entity -> { };

    /** Every worker of the pairing tests makes this many calls, and every call is scripted its own response. */
    private static final int WORKERS = 8;
    private static final int CALLS_PER_WORKER = 1_500;
    private static final int PAIRED_CALLS = WORKERS * CALLS_PER_WORKER;
    private static final String RESPONSE = "response ";

    // --- the premise of this task: the engine's short-circuit is visible in the probes -----------------

    @Test
    void rejectedGuardPreventsLaterGuardAndActionCalls() {
        CallTrace trace = CallTrace.create();
        GuardProbe<Entity, State, Id> first = GuardProbe.<Entity, State, Id>create("first", trace)
                .thenReturn(GuardResult.reject("no title"));
        GuardProbe<Entity, State, Id> second = GuardProbe.<Entity, State, Id>create("second", trace)
                .otherwiseReturn(GuardResult.pass());
        ActionProbe<Entity, State, Id> action = ActionProbe.<Entity, State, Id>create("prepare", trace)
                .otherwiseReturn(ActionOutcome.ok());
        GraphDefinition<Entity, State, Id> graph = StateGraph.define(TestDomain.KEY).initial(State.NEW)
                .terminal(State.DONE).terminal(State.FAILED)
                .statePolicy(State.NEW, "x").statePolicy(State.READY, "x")
                .transition(Id.PREPARE, State.NEW, State.READY).action("prepare", action)
                    .guard("first", first).guard("second", second).endTransition()
                .transition(Id.RECOVER, State.NEW, State.FAILED).endTransition()
                .transition(Id.FINISH, State.READY, State.DONE).endTransition()
                .build();
        try (MachineRegistry registry = TestDomain.registry(graph)) {
            registry.of(TestDomain.KEY).execute(new Entity("e", State.NEW), Id.PREPARE);
        }
        trace.assertNamesExactly("first");
        second.calls().assertCount(0);
        action.calls().assertCount(0);
    }

    @Test
    void guardsThenTheActionThenTheHookShareOneOrder() {
        CallTrace trace = CallTrace.create();
        GuardProbe<Entity, State, Id> first = GuardProbe.<Entity, State, Id>create("first", trace)
                .otherwiseReturn(GuardResult.pass());
        GuardProbe<Entity, State, Id> second = GuardProbe.<Entity, State, Id>create("second", trace)
                .otherwiseReturn(GuardResult.pass());
        ActionProbe<Entity, State, Id> action = ActionProbe.<Entity, State, Id>create("prepare", trace)
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
        GuardProbe<Entity, State, Id> first = GuardProbe.<Entity, State, Id>create("first", trace)
                .otherwiseReturn(GuardResult.pass());
        IllegalStateException boom = new IllegalStateException("no stock");
        ActionProbe<Entity, State, Id> action = ActionProbe.<Entity, State, Id>create("prepare", trace).thenThrow(boom);
        HookProbe<Entity> hook = HookProbe.<Entity>create("audit", trace).otherwiseSucceed();
        Entity entity = new Entity("e", State.NEW);
        TransitionAbortedException aborted = assertThrows(TransitionAbortedException.class,
                () -> transition(graph(first, PASS_SECOND, action, hook), entity));
        assertSame(boom, aborted.getCause());
        trace.assertNamesExactly("first", "prepare");
        hook.calls().assertCount(0);
        assertEquals(State.NEW, entity.state());
    }

    @Test
    void anEarlierHookFailureCanLeaveALaterProbeEmptyAfterBothStatesCommit() {
        Entity root = new Entity("root", State.NEW);
        Entity child = new Entity("child", State.NEW);
        IllegalStateException boom = new IllegalStateException("first hook failed");
        HookProbe<Entity> later = HookProbe.<Entity>create("hook", CallTrace.create()).otherwiseSucceed();
        GraphDefinition<Entity, State, Id> graph = StateGraph.define(TestDomain.KEY).initial(State.NEW)
                .terminal(State.DONE).terminal(State.FAILED)
                .statePolicy(State.NEW, "x").statePolicy(State.READY, "x")
                .transition(Id.RECOVER, State.NEW, State.FAILED).endTransition()
                .transition(Id.FINISH, State.READY, State.DONE).endTransition()
                .transition(Id.PREPARE, State.NEW, State.READY).action("prepare", context -> {
                    if (context.entity() == root) {
                        context.cascade(TestDomain.KEY, child, Id.PREPARE);
                    }
                    return ActionOutcome.ok();
                }).afterCommit("hook", entity -> {
                    if (entity == root) {
                        throw boom;
                    }
                    later.accept(entity);
                }).endTransition()
                .build();

        try (MachineRegistry registry = TestDomain.registry(graph)) {
            AfterCommitException failure = assertThrows(AfterCommitException.class,
                    () -> registry.of(TestDomain.KEY).execute(root, Id.PREPARE));
            assertSame(boom, failure.getCause());
        }
        assertEquals(State.READY, root.state());
        assertEquals(State.READY, child.state());
        later.calls().assertCount(0);
    }

    // --- scripting ------------------------------------------------------------------------------------

    @Test
    void queuedResponsesAreServedInTheOrderTheyWereScripted() {
        CallTrace trace = CallTrace.create();
        ActionProbe<Entity, State, Id> action = ActionProbe.<Entity, State, Id>create("prepare", trace)
                .thenReturn(ActionOutcome.ok())
                .thenReturn(ActionOutcome.failed("no stock"));
        HookProbe<Entity> hook = HookProbe.<Entity>create("audit", trace).otherwiseSucceed();
        GraphDefinition<Entity, State, Id> graph = graph(PASS_FIRST, PASS_SECOND, action, hook);
        Entity moved = new Entity("moved", State.NEW);
        Entity failed = new Entity("failed", State.NEW);
        try (MachineRegistry registry = TestDomain.registry(graph)) {
            registry.of(TestDomain.KEY).execute(moved, Id.PREPARE);
            registry.of(TestDomain.KEY).execute(failed, Id.PREPARE);
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
        ActionProbe<Entity, State, Id> action = ActionProbe.<Entity, State, Id>create("prepare", trace)
                .thenReturn(ActionOutcome.failed("first try"))
                .otherwiseReturn(ActionOutcome.ok());
        GraphDefinition<Entity, State, Id> graph = graph(PASS_FIRST, PASS_SECOND, action, NO_HOOK);
        List<Entity> entities = List.of(new Entity("a", State.NEW), new Entity("b", State.NEW),
                new Entity("c", State.NEW));
        try (MachineRegistry registry = TestDomain.registry(graph)) {
            for (Entity entity : entities) {
                registry.of(TestDomain.KEY).execute(entity, Id.PREPARE);
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
        ActionProbe<Entity, State, Id> action = ActionProbe.create("prepare", trace);
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
        GuardProbe<Entity, State, Id> first = GuardProbe.<Entity, State, Id>create("first", trace).thenThrow(boom);
        ActionProbe<Entity, State, Id> action = ActionProbe.<Entity, State, Id>create("prepare", trace)
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
        ActionProbe<Entity, State, Id> action = ActionProbe.<Entity, State, Id>create("prepare", trace)
                .thenReturn(ActionOutcome.ok())
                .thenThrow(boom)
                .onCall(context -> seen.add(context.entity().id() + ":" + context.to()));
        GraphDefinition<Entity, State, Id> graph = graph(PASS_FIRST, PASS_SECOND, action, NO_HOOK);
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
        GuardProbe<Entity, State, Id> first = GuardProbe.<Entity, State, Id>create("first", trace)
                .thenReturn(GuardResult.pass())
                .thenThrow(boom)
                .onCall(context -> seen.add(context.entity().id() + ":" + context.from()));
        ActionProbe<Entity, State, Id> action = ActionProbe.<Entity, State, Id>create("prepare", trace)
                .otherwiseReturn(ActionOutcome.ok());
        GraphDefinition<Entity, State, Id> graph = graph(first, PASS_SECOND, action, NO_HOOK);
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
        AtomicReference<TransitionContext<Entity, State, Id>> captured = new AtomicReference<>();
        ActionProbe<Entity, State, Id> action = ActionProbe.<Entity, State, Id>create("prepare", trace)
                .otherwiseReturn(ActionOutcome.ok())
                .onCall(captured::set);
        Entity entity = new Entity("e", State.NEW);
        transition(graph(PASS_FIRST, PASS_SECOND, action, NO_HOOK), entity);
        ActionCall<Entity, State, Id> call = action.calls().single();
        assertSame(entity, call.entity());
        assertEquals(State.NEW, call.from());
        assertEquals(Id.PREPARE, call.transition());
        assertEquals(State.READY, call.to());
        assertEquals(0, call.depth());
        assertEquals(State.READY, entity.state());
        assertThrows(StaleContextException.class, () -> captured.get().peer(TestDomain.KEY));
    }

    @Test
    void aGuardCallKeepsTheEntityAndTheEdgeItWasAskedAbout() {
        CallTrace trace = CallTrace.create();
        GuardProbe<Entity, State, Id> first = GuardProbe.<Entity, State, Id>create("first", trace)
                .otherwiseReturn(GuardResult.pass());
        ActionProbe<Entity, State, Id> action = ActionProbe.<Entity, State, Id>create("prepare", trace)
                .otherwiseReturn(ActionOutcome.ok());
        Entity entity = new Entity("e", State.NEW);
        transition(graph(first, PASS_SECOND, action, NO_HOOK), entity);
        GuardCall<Entity, State, Id> call = first.calls().single();
        assertSame(entity, call.entity());
        assertEquals(State.NEW, call.from());
        assertEquals(Id.PREPARE, call.transition());
        assertEquals(State.READY, call.to());
    }

    @Test
    void theTraceCarriesTheSameCallTheProbeRecorded() {
        CallTrace trace = CallTrace.create();
        GuardProbe<Entity, State, Id> first = GuardProbe.<Entity, State, Id>create("first", trace)
                .otherwiseReturn(GuardResult.pass());
        ActionProbe<Entity, State, Id> action = ActionProbe.<Entity, State, Id>create("prepare", trace)
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
        assertThrows(NullPointerException.class, () -> ActionProbe.<Entity, State, Id>create(null, trace));
        assertThrows(NullPointerException.class, () -> GuardProbe.<Entity, State, Id>create("first", null));
        assertThrows(NullPointerException.class, () -> HookProbe.<Entity>create("audit", null));
        assertThrows(IllegalArgumentException.class, () -> ActionProbe.<Entity, State, Id>create(" ", trace));
        assertThrows(IllegalArgumentException.class, () -> GuardProbe.<Entity, State, Id>create("", trace));
        assertThrows(IllegalArgumentException.class, () -> HookProbe.<Entity>create(" ", trace));
    }

    @Test
    void aProbeRejectsANullResponseOrSideEffect() {
        CallTrace trace = CallTrace.create();
        ActionProbe<Entity, State, Id> action = ActionProbe.create("prepare", trace);
        assertThrows(NullPointerException.class, () -> action.thenReturn(null));
        assertThrows(NullPointerException.class, () -> action.thenThrow(null));
        assertThrows(NullPointerException.class, () -> action.otherwiseReturn(null));
        assertThrows(NullPointerException.class, () -> action.onCall(null));
        GuardProbe<Entity, State, Id> guard = GuardProbe.create("first", trace);
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

    /**
     * Recording the call, tracing it and withdrawing its response are one step, so the invocation at position
     * p of {@code calls()} is the one the script served as call p + 1, which is the pairing {@link ProbeCalls}
     * documents. Under a lock of its own for each of the three, a thread preempted between the steps lets later
     * threads withdraw its ordinal, and the test reads another call's answer off its own position. The same
     * gap lets the probe's own entries in the shared trace run in another order than its calls.
     */
    @Test
    void concurrentHookCallsAreServedTheResponseOfTheirOwnPosition() throws Exception {
        CallTrace trace = CallTrace.create();
        HookProbe<Entity> hook = HookProbe.create("audit", trace);
        for (int ordinal = 1; ordinal <= PAIRED_CALLS; ordinal++) {
            hook.thenThrow(new IllegalStateException(RESPONSE + ordinal));
        }
        Map<Entity, Integer> served = serveConcurrently((Entity entity) -> {
            IllegalStateException response =
                    assertThrows(IllegalStateException.class, () -> hook.accept(entity));
            return ordinalOf(response.getMessage());
        });
        assertEveryCallWasServedItsOwnPosition(hook.calls(), trace, (Entity call) -> call, served);
    }

    @Test
    void concurrentGuardCallsAreServedTheVerdictOfTheirOwnPosition() throws Exception {
        CallTrace trace = CallTrace.create();
        GuardProbe<Entity, State, Id> guard = GuardProbe.create("gate", trace);
        for (int ordinal = 1; ordinal <= PAIRED_CALLS; ordinal++) {
            guard.thenReturn(GuardResult.reject(RESPONSE + ordinal));
        }
        Map<Entity, Integer> served = serveConcurrently((Entity entity) -> {
            GuardResult.Reject verdict = (GuardResult.Reject) guard.test(guardContext(entity));
            return ordinalOf(verdict.reason());
        });
        assertEveryCallWasServedItsOwnPosition(guard.calls(), trace, GuardCall::entity, served);
    }

    @Test
    void concurrentActionCallsAreServedTheOutcomeOfTheirOwnPosition() throws Exception {
        CallTrace trace = CallTrace.create();
        ActionProbe<Entity, State, Id> action = ActionProbe.create("prepare", trace);
        for (int ordinal = 1; ordinal <= PAIRED_CALLS; ordinal++) {
            action.thenReturn(ActionOutcome.failed(RESPONSE + ordinal));
        }
        Map<Entity, Integer> served = serveConcurrently((Entity entity) -> {
            ActionOutcome.Failed outcome = (ActionOutcome.Failed) action.process(actionContext(entity));
            return ordinalOf(outcome.reason());
        });
        assertEveryCallWasServedItsOwnPosition(action.calls(), trace, ActionCall::entity, served);
    }

    // --- fixtures -------------------------------------------------------------------------------------

    /**
     * Starts every worker at once, each calling the probe for entities of its own, and returns the ordinal of
     * the scripted response every entity was served.
     */
    private static Map<Entity, Integer> serveConcurrently(ToIntFunction<Entity> call) throws Exception {
        Map<Entity, Integer> served = new ConcurrentHashMap<>();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        try {
            List<Future<?>> workers = new ArrayList<>();
            for (int worker = 0; worker < WORKERS; worker++) {
                int id = worker;
                workers.add(pool.submit(() -> {
                    start.await();
                    for (int index = 0; index < CALLS_PER_WORKER; index++) {
                        Entity entity = new Entity(id + "/" + index, State.NEW);
                        served.put(entity, call.applyAsInt(entity));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> worker : workers) {
                worker.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        return served;
    }

    private static int ordinalOf(String response) {
        return Integer.parseInt(response.substring(RESPONSE.length()));
    }

    /** Position p of the probe's own list must have been served call p + 1, and sit at p in its own trace. */
    private static <C> void assertEveryCallWasServedItsOwnPosition(ProbeCalls<C> calls, CallTrace trace,
            Function<C, Entity> entityOf, Map<Entity, Integer> served) {
        List<C> recorded = calls.snapshot();
        List<TraceEvent> events = trace.events();
        assertEquals(PAIRED_CALLS, recorded.size());
        assertEquals(PAIRED_CALLS, events.size());
        int misPaired = 0;
        int outOfTraceOrder = 0;
        String firstMisPaired = "none";
        for (int position = 0; position < recorded.size(); position++) {
            int ordinal = served.get(entityOf.apply(recorded.get(position)));
            if (ordinal != position + 1) {
                misPaired++;
                if (misPaired == 1) {
                    firstMisPaired = "position " + position + " was served call " + ordinal;
                }
            }
            if (events.get(position).call() != recorded.get(position)) {
                outOfTraceOrder++;
            }
        }
        int misPairedCalls = misPaired;
        int outOfOrderEvents = outOfTraceOrder;
        String first = firstMisPaired;
        assertAll(
                () -> assertEquals(0, misPairedCalls,
                        "positions that were served the response of another call; the first: " + first),
                () -> assertEquals(0, outOfOrderEvents,
                        "trace entries of this probe that are not at the position of their call"));
    }

    /** A context that answers what a probe reads on entry and refuses everything else. */
    private static InvocationHandler contextOf(Entity entity) {
        return (Object proxy, Method method, Object[] arguments) -> switch (method.getName()) {
            case "entity" -> entity;
            case "from" -> State.NEW;
            case "transition" -> Id.PREPARE;
            case "to" -> State.READY;
            case "depth" -> 0;
            case "input" -> Optional.empty();
            default -> throw new UnsupportedOperationException(method.getName());
        };
    }

    @SuppressWarnings("unchecked")
    private static GuardContext<Entity, State, Id> guardContext(Entity entity) {
        return (GuardContext<Entity, State, Id>) Proxy.newProxyInstance(ProbeTest.class.getClassLoader(),
                new Class<?>[] {GuardContext.class}, contextOf(entity));
    }

    @SuppressWarnings("unchecked")
    private static TransitionContext<Entity, State, Id> actionContext(Entity entity) {
        return (TransitionContext<Entity, State, Id>) Proxy.newProxyInstance(ProbeTest.class.getClassLoader(),
                new Class<?>[] {TransitionContext.class}, contextOf(entity));
    }

    /**
     * NEW moves to READY through the action {@code prepare}, guarded by {@code first} and {@code second} and
     * followed by the hook {@code audit}; a failed outcome routes to FAILED, so the probe can script one
     * without aborting the transaction.
     */
    private static GraphDefinition<Entity, State, Id> graph(TransitionGuard<Entity, State, Id> first,
            TransitionGuard<Entity, State, Id> second, TransitionAction<Entity, State, Id> action,
            Consumer<? super Entity> hook) {
        return StateGraph.define(TestDomain.KEY).initial(State.NEW)
                .terminal(State.DONE).terminal(State.FAILED)
                .statePolicy(State.NEW, "x").statePolicy(State.READY, "x")
                .transition(Id.PREPARE, State.NEW, State.READY).action("prepare", action)
                    .guard("first", first).guard("second", second)
                    .onFailure(Id.RECOVER).afterCommit("audit", hook).endTransition()
                .transition(Id.RECOVER, State.NEW, State.FAILED).endTransition()
                .transition(Id.FINISH, State.READY, State.DONE).endTransition()
                .build();
    }

    private static void transition(GraphDefinition<Entity, State, Id> graph, Entity entity) {
        try (MachineRegistry registry = TestDomain.registry(graph)) {
            registry.of(TestDomain.KEY).execute(entity, Id.PREPARE);
        }
    }
}
