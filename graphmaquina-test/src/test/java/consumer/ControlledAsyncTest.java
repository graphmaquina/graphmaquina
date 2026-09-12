package consumer;

import static io.github.graphmaquina.test.fixtures.TestDomain.KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.AsyncTransitionAction;
import io.github.graphmaquina.api.AsyncTransitionGuard;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.runtime.MachineRegistry;
import io.github.graphmaquina.runtime.StateMachines;
import io.github.graphmaquina.test.AsyncAssertions;
import io.github.graphmaquina.test.GraphAssertionError;
import io.github.graphmaquina.test.fixtures.TestDomain;
import io.github.graphmaquina.test.fixtures.TestDomain.Entity;
import io.github.graphmaquina.test.fixtures.TestDomain.State;
import io.github.graphmaquina.test.probe.ActionCall;
import io.github.graphmaquina.test.probe.ControlledAsyncAction;
import io.github.graphmaquina.test.probe.ControlledAsyncGuard;
import io.github.graphmaquina.test.probe.GuardCall;
import io.github.graphmaquina.test.probe.PendingInvocation;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/**
 * Contract of the controlled asynchronous fixtures. Nothing here sleeps: the test resumes when the engine has
 * entered the action or the guard, and the engine resumes when the test answers that very invocation. Every
 * mismatch is read off the fields of the {@link GraphAssertionError}, not off its type.
 */
class ControlledAsyncTest {

    private static final MachineKey<Parcel, Status> PARCEL = MachineKey.of(Parcel.class, Status.class);
    private static final Duration BUDGET = Duration.ofMillis(50);
    private static final Duration GENEROUS = Duration.ofSeconds(2);

    // --- the premise of this task: the test decides when the action answers ----------------------------

    // The fixture is closed twice on purpose — once here and once by the try-with-resources — so the test
    // also covers that closing is idempotent; -Xlint:try flags exactly that shape.
    @SuppressWarnings("try")
    @Test
    void testControlsWhenTheActionCompletes() {
        try (ControlledAsyncAction<Entity, State> action = ControlledAsyncAction.<Entity, State>create();
             MachineRegistry registry = TestDomain.registry(TestDomain.graph(Duration.ofSeconds(2), action))) {
            Entity entity = new Entity("e", State.NEW);
            CompletableFuture<Outcome<Entity, State>> result =
                    registry.of(KEY).async().transitionTo(entity, State.READY);
            try {
                PendingInvocation<ActionCall<Entity, State>, ActionOutcome> call =
                        action.awaitInvocation(Duration.ofSeconds(2));
                assertEquals(State.NEW, entity.state());
                assertFalse(result.isDone());
                assertTrue(call.complete(ActionOutcome.ok()));
                AsyncAssertions.await(result, Duration.ofSeconds(2)).isApplied().movedTo(State.READY);
            } finally {
                action.close();
            }
        }
    }

    @Test
    void concurrentInvocationsAnswerIndependently() {
        ControlledAsyncAction<Entity, State> action = ControlledAsyncAction.create();
        MachineRegistry registry = TestDomain.registry(TestDomain.graph(Duration.ofSeconds(5), action));
        try (registry; action) {
            Entity first = new Entity("first", State.NEW);
            Entity second = new Entity("second", State.NEW);
            CompletableFuture<Outcome<Entity, State>> firstResult =
                    registry.of(KEY).async().transitionTo(first, State.READY);
            CompletableFuture<Outcome<Entity, State>> secondResult =
                    registry.of(KEY).async().transitionTo(second, State.READY);
            PendingInvocation<ActionCall<Entity, State>, ActionOutcome> one =
                    action.awaitInvocation(GENEROUS);
            PendingInvocation<ActionCall<Entity, State>, ActionOutcome> other =
                    action.awaitInvocation(GENEROUS);
            assertNotSame(one, other);
            assertNotSame(one.call().entity(), other.call().entity());
            assertEquals(2, action.invocations().size());

            PendingInvocation<ActionCall<Entity, State>, ActionOutcome> firstCall =
                    one.call().entity() == first ? one : other;
            PendingInvocation<ActionCall<Entity, State>, ActionOutcome> secondCall =
                    firstCall == one ? other : one;
            assertTrue(firstCall.complete(ActionOutcome.ok()));
            AsyncAssertions.await(firstResult, GENEROUS).isApplied().hasEntitySameAs(first);
            assertFalse(secondCall.isDone());
            assertFalse(secondResult.isDone());
            assertEquals(State.NEW, second.state());

            assertTrue(secondCall.complete(ActionOutcome.ok()));
            AsyncAssertions.await(secondResult, GENEROUS).isApplied().hasEntitySameAs(second);
        }
    }

    @Test
    void invocationsAreListedInEntryOrderAndTheListIsACopy() {
        ControlledAsyncAction<Entity, State> action = ControlledAsyncAction.create();
        MachineRegistry registry = TestDomain.registry(TestDomain.graph(Duration.ofSeconds(5), action));
        try (registry; action) {
            Entity entity = new Entity("e", State.NEW);
            CompletableFuture<Outcome<Entity, State>> result =
                    registry.of(KEY).async().transitionTo(entity, State.READY);
            PendingInvocation<ActionCall<Entity, State>, ActionOutcome> call = action.awaitInvocation(GENEROUS);
            List<PendingInvocation<ActionCall<Entity, State>, ActionOutcome>> recorded = action.invocations();
            assertEquals(List.of(call), recorded);
            assertSame(entity, call.call().entity());
            assertEquals(State.NEW, call.call().from());
            assertEquals(State.READY, call.call().to());
            assertEquals(0, call.call().depth());

            assertTrue(call.complete(ActionOutcome.ok()));
            AsyncAssertions.await(result, GENEROUS).isApplied().movedTo(State.READY);

            Entity later = new Entity("later", State.NEW);
            CompletableFuture<Outcome<Entity, State>> second =
                    registry.of(KEY).async().transitionTo(later, State.READY);
            assertTrue(action.awaitInvocation(GENEROUS).complete(ActionOutcome.ok()));
            AsyncAssertions.await(second, GENEROUS).isApplied().hasEntitySameAs(later);
            assertEquals(2, action.invocations().size());
            assertEquals(List.of(call), recorded, "a list already handed out does not grow");
        }
    }

    // --- the engine's own deadline, which is not the test's budget -------------------------------------

    @Test
    void anEngineTimeoutAbortsAndLeavesTheActionStagePending() {
        ControlledAsyncAction<Entity, State> action = ControlledAsyncAction.create();
        MachineRegistry registry = TestDomain.registry(TestDomain.graph(BUDGET, action));
        try (registry; action) {
            Entity entity = new Entity("e", State.NEW);
            CompletableFuture<Outcome<Entity, State>> result =
                    registry.of(KEY).async().transitionTo(entity, State.READY);
            PendingInvocation<ActionCall<Entity, State>, ActionOutcome> call = action.awaitInvocation(GENEROUS);
            AsyncAssertions.awaitFailure(result, GENEROUS).isAbort()
                    .at(KEY, entity, State.NEW, State.READY)
                    .hasActionNamed("prepare")
                    .hasCause(TimeoutException.class);
            assertFalse(call.isDone());
            assertTrue(call.complete(ActionOutcome.ok()));
            assertEquals(State.NEW, entity.state());
        }
    }

    // --- an asynchronous guard decides before the action is ever entered -------------------------------

    @Test
    void anAsyncGuardRejectionLeavesTheActionUnreached() {
        ControlledAsyncGuard<Parcel, Status> gate = ControlledAsyncGuard.create();
        ControlledAsyncAction<Parcel, Status> send = ControlledAsyncAction.create();
        CompletableFuture<String> seen = new CompletableFuture<>();
        gate.onCall(context -> seen.complete(context.entity().id() + ":" + context.to()));
        MachineRegistry registry = gatedRegistry(gate, send);
        try (registry; gate; send) {
            Parcel parcel = new Parcel("p", Status.NEW);
            CompletableFuture<Outcome<Parcel, Status>> result =
                    registry.of(PARCEL).async().transitionTo(parcel, Status.SENT);
            PendingInvocation<GuardCall<Parcel, Status>, GuardResult> call = gate.awaitInvocation(GENEROUS);
            assertEquals("p:SENT", seen.getNow(null));
            assertEquals(List.of(call), gate.invocations());
            assertSame(parcel, call.call().entity());
            assertEquals(Status.NEW, call.call().from());
            assertEquals(Status.SENT, call.call().to());
            assertTrue(call.complete(GuardResult.reject("closed for the day")));
            AsyncAssertions.await(result, GENEROUS).isRejected()
                    .isRejectedByGuardNamed("gate")
                    .hasReason("closed for the day")
                    .hasRecordedState(Status.NEW);
            assertEquals(List.of(), send.invocations());
            assertEquals(Status.NEW, parcel.status());
        }
    }

    @Test
    void guardInvocationsAreListedInEntryOrderAndTheListIsACopy() {
        ControlledAsyncGuard<Parcel, Status> gate = ControlledAsyncGuard.create();
        ControlledAsyncAction<Parcel, Status> send = ControlledAsyncAction.create();
        MachineRegistry registry = gatedRegistry(gate, send);
        try (registry; gate; send) {
            Parcel first = new Parcel("first", Status.NEW);
            CompletableFuture<Outcome<Parcel, Status>> firstResult =
                    registry.of(PARCEL).async().transitionTo(first, Status.SENT);
            PendingInvocation<GuardCall<Parcel, Status>, GuardResult> call = gate.awaitInvocation(GENEROUS);
            List<PendingInvocation<GuardCall<Parcel, Status>, GuardResult>> recorded = gate.invocations();
            assertEquals(List.of(call), recorded);
            assertTrue(call.complete(GuardResult.reject("closed for the day")));
            AsyncAssertions.await(firstResult, GENEROUS).isRejected().hasReason("closed for the day");

            Parcel later = new Parcel("later", Status.NEW);
            CompletableFuture<Outcome<Parcel, Status>> secondResult =
                    registry.of(PARCEL).async().transitionTo(later, Status.SENT);
            assertTrue(gate.awaitInvocation(GENEROUS).complete(GuardResult.reject("still closed")));
            AsyncAssertions.await(secondResult, GENEROUS).isRejected().hasReason("still closed");
            assertEquals(2, gate.invocations().size());
            assertEquals(List.of(call), recorded, "a list already handed out does not grow");
        }
    }

    @Test
    void closingTheGuardFixtureCancelsItsPendingInvocations() {
        ControlledAsyncGuard<Parcel, Status> gate = ControlledAsyncGuard.create();
        ControlledAsyncAction<Parcel, Status> send = ControlledAsyncAction.create();
        MachineRegistry registry = gatedRegistry(gate, send);
        try (registry; send) {
            Parcel parcel = new Parcel("p", Status.NEW);
            CompletableFuture<Outcome<Parcel, Status>> result =
                    registry.of(PARCEL).async().transitionTo(parcel, Status.SENT);
            PendingInvocation<GuardCall<Parcel, Status>, GuardResult> call = gate.awaitInvocation(GENEROUS);
            gate.close();
            gate.close();
            assertTrue(call.isCancelled());
            assertFalse(call.complete(GuardResult.pass()));
            AsyncAssertions.awaitFailure(result, GENEROUS).isAbort().hasCause(CancellationException.class);
            assertEquals(Status.NEW, parcel.status());
        }
    }

    @Test
    void aClosedGuardFixtureRefusesFurtherInvocations() {
        ControlledAsyncGuard<Parcel, Status> gate = ControlledAsyncGuard.create();
        ControlledAsyncAction<Parcel, Status> send = ControlledAsyncAction.create();
        gate.close();
        MachineRegistry registry = gatedRegistry(gate, send);
        try (registry; send) {
            Parcel parcel = new Parcel("p", Status.NEW);
            CompletableFuture<Outcome<Parcel, Status>> result =
                    registry.of(PARCEL).async().transitionTo(parcel, Status.SENT);
            AsyncAssertions.awaitFailure(result, GENEROUS).isAbort().hasCause(IllegalStateException.class);
            assertEquals(List.of(), gate.invocations());
            assertEquals(Status.NEW, parcel.status());
        }
    }

    @Test
    void aGuardSideEffectThatThrowsSettlesItsOwnInvocation() {
        IllegalStateException boom = new IllegalStateException("gate hook down");
        ControlledAsyncGuard<Parcel, Status> gate = ControlledAsyncGuard.create();
        ControlledAsyncAction<Parcel, Status> send = ControlledAsyncAction.create();
        gate.onCall(context -> {
            throw boom;
        });
        MachineRegistry registry = gatedRegistry(gate, send);
        try (registry; gate; send) {
            Parcel parcel = new Parcel("p", Status.NEW);
            CompletableFuture<Outcome<Parcel, Status>> result =
                    registry.of(PARCEL).async().transitionTo(parcel, Status.SENT);
            AsyncAssertions.awaitFailure(result, GENEROUS).isAbort().hasCauseSameAs(boom);
            List<PendingInvocation<GuardCall<Parcel, Status>, GuardResult>> recorded = gate.invocations();
            assertEquals(1, recorded.size());
            assertTrue(recorded.get(0).isDone());
            assertFalse(recorded.get(0).complete(GuardResult.pass()));
            assertEquals(List.of(), send.invocations());
        }
    }

    // --- closing the control ---------------------------------------------------------------------------

    @Test
    void closingCancelsPendingInvocationsAndIsSafeTwice() {
        ControlledAsyncAction<Entity, State> action = ControlledAsyncAction.create();
        try (MachineRegistry registry = TestDomain.registry(TestDomain.graph(Duration.ofSeconds(30), action))) {
            Entity entity = new Entity("e", State.NEW);
            CompletableFuture<Outcome<Entity, State>> result =
                    registry.of(KEY).async().transitionTo(entity, State.READY);
            PendingInvocation<ActionCall<Entity, State>, ActionOutcome> call = action.awaitInvocation(GENEROUS);
            action.close();
            action.close();
            assertTrue(call.isDone());
            assertTrue(call.isCancelled());
            assertFalse(call.complete(ActionOutcome.ok()));
            AsyncAssertions.awaitFailure(result, GENEROUS).isAbort().hasCause(CancellationException.class);
            assertEquals(State.NEW, entity.state());
        }
    }

    @Test
    void aClosedFixtureRefusesFurtherInvocations() {
        ControlledAsyncAction<Entity, State> action = ControlledAsyncAction.create();
        action.close();
        MachineRegistry registry = TestDomain.registry(TestDomain.graph(GENEROUS, action));
        try (registry; action) {
            Entity entity = new Entity("e", State.NEW);
            CompletableFuture<Outcome<Entity, State>> result =
                    registry.of(KEY).async().transitionTo(entity, State.READY);
            AsyncAssertions.awaitFailure(result, GENEROUS).isAbort().hasCause(IllegalStateException.class);
            assertEquals(List.of(), action.invocations());
            assertEquals(State.NEW, entity.state());
        }
    }

    // --- a side effect that throws must not leave a stage nobody will answer ---------------------------

    @Test
    void anOnCallThatThrowsSettlesItsOwnInvocationAndPublishesNoHandle() {
        IllegalStateException boom = new IllegalStateException("hook down");
        ControlledAsyncAction<Entity, State> action = ControlledAsyncAction.<Entity, State>create()
                .onCall(context -> {
                    throw boom;
                });
        MachineRegistry registry = TestDomain.registry(TestDomain.graph(GENEROUS, action));
        try (registry; action) {
            Entity entity = new Entity("e", State.NEW);
            CompletableFuture<Outcome<Entity, State>> result =
                    registry.of(KEY).async().transitionTo(entity, State.READY);
            AsyncAssertions.awaitFailure(result, GENEROUS).isAbort().hasCauseSameAs(boom);
            List<PendingInvocation<ActionCall<Entity, State>, ActionOutcome>> recorded = action.invocations();
            assertEquals(1, recorded.size());
            assertTrue(recorded.get(0).isDone());
            assertFalse(recorded.get(0).complete(ActionOutcome.ok()));
            GraphAssertionError error = assertThrows(GraphAssertionError.class,
                    () -> action.awaitInvocation(BUDGET));
            assertEquals("no invocation arrived", error.actual());
        }
    }

    // A failed JUnit assertion inside the side effect is an AssertionError, not a RuntimeException, and it
    // must settle its invocation just the same: otherwise the stage stays pending and the test reads
    // "no invocation arrived" instead of its own broken assertion.
    @Test
    void anOnCallThatThrowsAnErrorSettlesItsOwnInvocationAndPublishesNoHandle() {
        AssertionError boom = new AssertionError("hook assertion failed");
        ControlledAsyncAction<Entity, State> action = ControlledAsyncAction.<Entity, State>create()
                .onCall(context -> {
                    throw boom;
                });
        MachineRegistry registry = TestDomain.registry(TestDomain.graph(GENEROUS, action));
        try (registry; action) {
            Entity entity = new Entity("e", State.NEW);
            CompletableFuture<Outcome<Entity, State>> result =
                    registry.of(KEY).async().transitionTo(entity, State.READY);
            ExecutionException raised =
                    assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
            assertSame(boom, raised.getCause());
            List<PendingInvocation<ActionCall<Entity, State>, ActionOutcome>> recorded = action.invocations();
            assertEquals(1, recorded.size());
            assertTrue(recorded.get(0).isDone(), "the stage of a hook that threw an Error is settled");
            assertFalse(recorded.get(0).complete(ActionOutcome.ok()));
            GraphAssertionError error = assertThrows(GraphAssertionError.class,
                    () -> action.awaitInvocation(BUDGET));
            assertEquals("no invocation arrived", error.actual());
            assertEquals(State.NEW, entity.state());
        }
    }

    @Test
    void aGuardSideEffectThatThrowsAnErrorSettlesItsOwnInvocation() {
        AssertionError boom = new AssertionError("gate assertion failed");
        ControlledAsyncGuard<Parcel, Status> gate = ControlledAsyncGuard.create();
        ControlledAsyncAction<Parcel, Status> send = ControlledAsyncAction.create();
        gate.onCall(context -> {
            throw boom;
        });
        MachineRegistry registry = gatedRegistry(gate, send);
        try (registry; gate; send) {
            Parcel parcel = new Parcel("p", Status.NEW);
            CompletableFuture<Outcome<Parcel, Status>> result =
                    registry.of(PARCEL).async().transitionTo(parcel, Status.SENT);
            ExecutionException raised =
                    assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
            assertSame(boom, raised.getCause());
            List<PendingInvocation<GuardCall<Parcel, Status>, GuardResult>> recorded = gate.invocations();
            assertEquals(1, recorded.size());
            assertTrue(recorded.get(0).isDone(), "the stage of a hook that threw an Error is settled");
            assertFalse(recorded.get(0).complete(GuardResult.pass()));
            GraphAssertionError error = assertThrows(GraphAssertionError.class,
                    () -> gate.awaitInvocation(BUDGET));
            assertEquals("no invocation arrived", error.actual());
            assertEquals(List.of(), send.invocations());
            assertEquals(Status.NEW, parcel.status());
        }
    }

    @Test
    void aHandleIsPublishedOnlyOnceTheSideEffectHasReturned() throws Exception {
        ControlledAsyncAction<Entity, State> action = ControlledAsyncAction.create();
        CompletableFuture<Void> release = new CompletableFuture<>();
        CompletableFuture<String> seen = new CompletableFuture<>();
        action.onCall(context -> {
            seen.complete(context.entity().id() + ":" + context.from() + ">" + context.to());
            release.join();
        });
        MachineRegistry registry = TestDomain.registry(TestDomain.graph(Duration.ofSeconds(30), action));
        try (registry; action) {
            Entity entity = new Entity("e", State.NEW);
            CompletableFuture<Outcome<Entity, State>> result =
                    registry.of(KEY).async().transitionTo(entity, State.READY);
            try {
                assertEquals("e:NEW>READY", seen.get(2, TimeUnit.SECONDS),
                        "the side effect reads the live context");
                assertThrows(GraphAssertionError.class, () -> action.awaitInvocation(BUDGET),
                        "no handle while the side effect is still running");
            } finally {
                release.complete(null);
            }
            PendingInvocation<ActionCall<Entity, State>, ActionOutcome> call = action.awaitInvocation(GENEROUS);
            assertTrue(call.complete(ActionOutcome.ok()));
            AsyncAssertions.await(result, GENEROUS).isApplied().movedTo(State.READY);
        }
    }

    // --- the fixture's own wait is the test's budget, not the machine's --------------------------------

    @Test
    void awaitInvocationFailsAsAnAssertionWhenNoInvocationArrives() {
        ControlledAsyncAction<Entity, State> action = ControlledAsyncAction.create();
        GraphAssertionError error =
                assertThrows(GraphAssertionError.class, () -> action.awaitInvocation(BUDGET));
        assertEquals("controlled async action", error.context());
        assertEquals("invocation", error.property());
        assertEquals("within " + BUDGET, error.expected());
        assertEquals("no invocation arrived", error.actual());
        action.close();
    }

    @Test
    void theGuardFixtureFailsItsOwnWaitUnderItsOwnContext() {
        ControlledAsyncGuard<Parcel, Status> gate = ControlledAsyncGuard.create();
        GraphAssertionError error =
                assertThrows(GraphAssertionError.class, () -> gate.awaitInvocation(BUDGET));
        assertEquals("controlled async guard", error.context());
        assertEquals("invocation", error.property());
        assertEquals("within " + BUDGET, error.expected());
        assertEquals("no invocation arrived", error.actual());
        gate.close();
    }

    @Test
    void anInterruptedAwaitInvocationKeepsTheInterruptFlag() {
        ControlledAsyncAction<Entity, State> action = ControlledAsyncAction.create();
        Thread.currentThread().interrupt();
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> action.awaitInvocation(Duration.ofSeconds(30)));
        assertTrue(Thread.interrupted(), "the wait must leave the interrupt flag set");
        assertEquals("controlled async action", error.context());
        assertEquals("an invocation", error.expected());
        assertEquals("test thread interrupted", error.actual());
        action.close();
    }

    @Test
    void anInterruptedGuardWaitKeepsTheInterruptFlag() {
        ControlledAsyncGuard<Parcel, Status> gate = ControlledAsyncGuard.create();
        Thread.currentThread().interrupt();
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> gate.awaitInvocation(Duration.ofSeconds(30)));
        assertTrue(Thread.interrupted(), "the wait must leave the interrupt flag set");
        assertEquals("controlled async guard", error.context());
        assertEquals("test thread interrupted", error.actual());
        gate.close();
    }

    @Test
    void aBudgetThatIsNotPositiveOrDoesNotFitInNanosecondsIsRejected() {
        ControlledAsyncAction<Entity, State> action = ControlledAsyncAction.create();
        ControlledAsyncGuard<Parcel, Status> gate = ControlledAsyncGuard.create();
        Duration tooLong = Duration.ofSeconds(Long.MAX_VALUE);
        assertThrows(NullPointerException.class, () -> action.awaitInvocation(null));
        assertThrows(NullPointerException.class, () -> gate.awaitInvocation(null));
        assertEquals("the budget must be positive, not PT0S",
                assertThrows(IllegalArgumentException.class,
                        () -> action.awaitInvocation(Duration.ZERO)).getMessage());
        assertThrows(IllegalArgumentException.class, () -> action.awaitInvocation(Duration.ofMillis(-1)));
        assertThrows(IllegalArgumentException.class, () -> gate.awaitInvocation(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> gate.awaitInvocation(Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class, () -> action.awaitInvocation(tooLong));
        assertThrows(IllegalArgumentException.class, () -> gate.awaitInvocation(tooLong));
        action.close();
        gate.close();
    }

    @Test
    void aHandleRejectsANullAnswer() {
        ControlledAsyncAction<Entity, State> action = ControlledAsyncAction.create();
        MachineRegistry registry = TestDomain.registry(TestDomain.graph(GENEROUS, action));
        try (registry; action) {
            Entity entity = new Entity("e", State.NEW);
            registry.of(KEY).async().transitionTo(entity, State.READY);
            PendingInvocation<ActionCall<Entity, State>, ActionOutcome> call = action.awaitInvocation(GENEROUS);
            assertThrows(NullPointerException.class, () -> call.complete(null));
            assertThrows(NullPointerException.class, () -> call.fail(null));
            assertFalse(call.isDone());
            assertTrue(call.complete(ActionOutcome.ok()));
        }
    }

    @Test
    void aFailedInvocationReachesTheEngineAsTheCauseOfTheAbort() {
        ControlledAsyncAction<Entity, State> action = ControlledAsyncAction.create();
        IllegalStateException boom = new IllegalStateException("remote call refused");
        MachineRegistry registry = TestDomain.registry(TestDomain.graph(GENEROUS, action));
        try (registry; action) {
            Entity entity = new Entity("e", State.NEW);
            CompletableFuture<Outcome<Entity, State>> result =
                    registry.of(KEY).async().transitionTo(entity, State.READY);
            PendingInvocation<ActionCall<Entity, State>, ActionOutcome> call = action.awaitInvocation(GENEROUS);
            assertTrue(call.fail(boom));
            assertFalse(call.fail(boom));
            assertTrue(call.isDone());
            assertFalse(call.isCancelled());
            AsyncAssertions.awaitFailure(result, GENEROUS).isAbort().hasCauseSameAs(boom);
            assertEquals(State.NEW, entity.state());
        }
    }

    private static MachineRegistry gatedRegistry(AsyncTransitionGuard<Parcel, Status> guard,
                                                 AsyncTransitionAction<Parcel, Status> action) {
        GraphDefinition<Parcel, Status> graph = StateGraph.define(PARCEL)
                .initial(Status.NEW).terminal(Status.SENT)
                .from(Status.NEW)
                    .to(Status.SENT).asyncAction("send", action).asyncGuard("gate", guard)
                    .stay().forbidOthers("only sending")
                .from(Status.SENT).stay().forbidOthers("sent is final")
                .build();
        return MachineRegistry.builder()
                .add(StateMachines.from(graph).state(Parcel::status, Parcel::setStatus)
                        .identity(Parcel::id).asyncTimeout(Duration.ofSeconds(5)))
                .build();
    }

    private enum Status { NEW, SENT }

    private static final class Parcel {

        private final String id;
        private Status status;

        Parcel(String id, Status status) {
            this.id = id;
            this.status = status;
        }

        String id() {
            return id;
        }

        Status status() {
            return status;
        }

        void setStatus(Status status) {
            this.status = status;
        }
    }
}
