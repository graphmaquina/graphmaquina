package consumer;

import static io.github.graphmaquina.test.fixtures.TestDomain.KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.AsyncTransitionAction;
import io.github.graphmaquina.api.AsyncTransitionGuard;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.runtime.MachineRegistry;
import io.github.graphmaquina.runtime.StateMachines;
import io.github.graphmaquina.test.AsyncAssertions;
import io.github.graphmaquina.test.GraphAssertionError;
import io.github.graphmaquina.test.fixtures.PublicationFixture;
import io.github.graphmaquina.test.fixtures.PublicationFixture.ProviderRejectedException;
import io.github.graphmaquina.test.fixtures.PublicationFixture.PublicationFailures;
import io.github.graphmaquina.test.fixtures.PublicationFixture.ServiceUnavailableException;
import io.github.graphmaquina.test.fixtures.PublicationFixture.Ticket;
import io.github.graphmaquina.test.fixtures.PublicationFixture.TicketChange;
import io.github.graphmaquina.test.fixtures.PublicationFixture.TicketState;
import io.github.graphmaquina.test.fixtures.TestDomain;
import io.github.graphmaquina.test.fixtures.TestDomain.Entity;
import io.github.graphmaquina.test.fixtures.TestDomain.Id;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Contract of the controlled asynchronous fixtures. Nothing here sleeps: the test resumes when the engine has
 * entered the action or the guard, and the engine resumes when the test answers that very invocation. A mismatch
 * is read off the fields of the {@link GraphAssertionError}, not off its type, except in the two cases that
 * only need an execution or a wait to fail as an assertion.
 */
class ControlledAsyncTest {

    private static final MachineKey<Parcel, Status, Move> PARCEL =
            MachineKey.of(Parcel.class, Status.class, Move.class);
    private static final Duration BUDGET = Duration.ofMillis(50);
    private static final Duration GENEROUS = Duration.ofSeconds(2);

    // --- the premise of this task: the test decides when the action answers ----------------------------

    // This test alone lists the fixture before the registry, the reverse of the order docs/testing.md
    // recommends, because it closes the fixture twice on purpose — once in the finally block and once by the
    // try-with-resources — so it also covers that closing is idempotent; -Xlint:try flags exactly that shape.
    // The finally block closes the fixture while the registry is still open, so the header's order costs this
    // test nothing. Every other test lists the registry first, so that the fixture is the one closed first.
    @SuppressWarnings("try")
    @Test
    void testControlsWhenTheActionCompletes() {
        try (ControlledAsyncAction<Entity, State, Id> action = ControlledAsyncAction.<Entity, State, Id>create();
             MachineRegistry registry = TestDomain.registry(TestDomain.graph(Duration.ofSeconds(2), action))) {
            Entity entity = new Entity("e", State.NEW);
            CompletableFuture<Outcome<Entity, State, Id>> result =
                    registry.of(KEY).async().execute(entity, Id.PREPARE);
            try {
                PendingInvocation<ActionCall<Entity, State, Id>, ActionOutcome> call =
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
        ControlledAsyncAction<Entity, State, Id> action = ControlledAsyncAction.create();
        MachineRegistry registry = TestDomain.registry(TestDomain.graph(Duration.ofSeconds(5), action));
        try (registry; action) {
            Entity first = new Entity("first", State.NEW);
            Entity second = new Entity("second", State.NEW);
            CompletableFuture<Outcome<Entity, State, Id>> firstResult =
                    registry.of(KEY).async().execute(first, Id.PREPARE);
            CompletableFuture<Outcome<Entity, State, Id>> secondResult =
                    registry.of(KEY).async().execute(second, Id.PREPARE);
            PendingInvocation<ActionCall<Entity, State, Id>, ActionOutcome> one =
                    action.awaitInvocation(GENEROUS);
            PendingInvocation<ActionCall<Entity, State, Id>, ActionOutcome> other =
                    action.awaitInvocation(GENEROUS);
            assertNotSame(one, other);
            assertNotSame(one.call().entity(), other.call().entity());
            assertEquals(2, action.invocations().size());

            PendingInvocation<ActionCall<Entity, State, Id>, ActionOutcome> firstCall =
                    one.call().entity() == first ? one : other;
            PendingInvocation<ActionCall<Entity, State, Id>, ActionOutcome> secondCall =
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
        ControlledAsyncAction<Entity, State, Id> action = ControlledAsyncAction.create();
        MachineRegistry registry = TestDomain.registry(TestDomain.graph(Duration.ofSeconds(5), action));
        try (registry; action) {
            Entity entity = new Entity("e", State.NEW);
            CompletableFuture<Outcome<Entity, State, Id>> result =
                    registry.of(KEY).async().execute(entity, Id.PREPARE);
            PendingInvocation<ActionCall<Entity, State, Id>, ActionOutcome> call = action.awaitInvocation(GENEROUS);
            List<PendingInvocation<ActionCall<Entity, State, Id>, ActionOutcome>> recorded = action.invocations();
            assertEquals(List.of(call), recorded);
            assertSame(entity, call.call().entity());
            assertEquals(State.NEW, call.call().from());
            assertEquals(State.READY, call.call().to());
            assertEquals(0, call.call().depth());

            assertTrue(call.complete(ActionOutcome.ok()));
            AsyncAssertions.await(result, GENEROUS).isApplied().movedTo(State.READY);

            Entity later = new Entity("later", State.NEW);
            CompletableFuture<Outcome<Entity, State, Id>> second =
                    registry.of(KEY).async().execute(later, Id.PREPARE);
            PendingInvocation<ActionCall<Entity, State, Id>, ActionOutcome> laterCall =
                    action.awaitInvocation(GENEROUS);
            assertTrue(laterCall.complete(ActionOutcome.ok()));
            AsyncAssertions.await(second, GENEROUS).isApplied().hasEntitySameAs(later);
            List<PendingInvocation<ActionCall<Entity, State, Id>, ActionOutcome>> history = action.invocations();
            assertEquals(List.of(call, laterCall), history, "the invocation that entered first is listed first");
            assertSame(entity, history.get(0).call().entity());
            assertSame(later, history.get(1).call().entity());
            assertEquals(List.of(call), recorded, "a list already handed out does not grow");
        }
    }

    // --- the engine's own deadline, which is not the test's budget -------------------------------------

    @Test
    void anEngineTimeoutAbortsAndLeavesTheActionStagePending() {
        ControlledAsyncAction<Entity, State, Id> action = ControlledAsyncAction.create();
        MachineRegistry registry = TestDomain.registry(TestDomain.graph(BUDGET, action));
        try (registry; action) {
            Entity entity = new Entity("e", State.NEW);
            CompletableFuture<Outcome<Entity, State, Id>> result =
                    registry.of(KEY).async().execute(entity, Id.PREPARE);
            PendingInvocation<ActionCall<Entity, State, Id>, ActionOutcome> call = action.awaitInvocation(GENEROUS);
            AsyncAssertions.awaitFailure(result, GENEROUS).isAbort()
                    .at(KEY, entity, State.NEW, Id.PREPARE, State.READY)
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
        ControlledAsyncGuard<Parcel, Status, Move> gate = ControlledAsyncGuard.create();
        ControlledAsyncAction<Parcel, Status, Move> send = ControlledAsyncAction.create();
        CompletableFuture<String> seen = new CompletableFuture<>();
        gate.onCall(context -> seen.complete(context.entity().id() + ":" + context.to()));
        MachineRegistry registry = gatedRegistry(gate, send);
        try (registry; gate; send) {
            Parcel parcel = new Parcel("p", Status.NEW);
            CompletableFuture<Outcome<Parcel, Status, Move>> result =
                    registry.of(PARCEL).async().execute(parcel, Move.SEND);
            PendingInvocation<GuardCall<Parcel, Status, Move>, GuardResult> call = gate.awaitInvocation(GENEROUS);
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
        ControlledAsyncGuard<Parcel, Status, Move> gate = ControlledAsyncGuard.create();
        ControlledAsyncAction<Parcel, Status, Move> send = ControlledAsyncAction.create();
        MachineRegistry registry = gatedRegistry(gate, send);
        try (registry; gate; send) {
            Parcel first = new Parcel("first", Status.NEW);
            CompletableFuture<Outcome<Parcel, Status, Move>> firstResult =
                    registry.of(PARCEL).async().execute(first, Move.SEND);
            PendingInvocation<GuardCall<Parcel, Status, Move>, GuardResult> call = gate.awaitInvocation(GENEROUS);
            List<PendingInvocation<GuardCall<Parcel, Status, Move>, GuardResult>> recorded = gate.invocations();
            assertEquals(List.of(call), recorded);
            assertTrue(call.complete(GuardResult.reject("closed for the day")));
            AsyncAssertions.await(firstResult, GENEROUS).isRejected().hasReason("closed for the day");

            Parcel later = new Parcel("later", Status.NEW);
            CompletableFuture<Outcome<Parcel, Status, Move>> secondResult =
                    registry.of(PARCEL).async().execute(later, Move.SEND);
            PendingInvocation<GuardCall<Parcel, Status, Move>, GuardResult> laterCall =
                    gate.awaitInvocation(GENEROUS);
            assertTrue(laterCall.complete(GuardResult.reject("still closed")));
            AsyncAssertions.await(secondResult, GENEROUS).isRejected().hasReason("still closed");
            List<PendingInvocation<GuardCall<Parcel, Status, Move>, GuardResult>> history = gate.invocations();
            assertEquals(List.of(call, laterCall), history, "the invocation that entered first is listed first");
            assertSame(first, history.get(0).call().entity());
            assertSame(later, history.get(1).call().entity());
            assertEquals(List.of(call), recorded, "a list already handed out does not grow");
        }
    }

    /**
     * As for the action fixture, a guard handle is delivered once its side effect has returned, so delivery can
     * overtake an invocation that entered earlier, while the history keeps the order in which they entered.
     */
    @Test
    void guardDeliveryFollowsPublicationAfterSuccessfulSideEffectsWhileHistoryKeepsEntryOrder() {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ControlledAsyncGuard<Parcel, Status, Move> gate = ControlledAsyncGuard.<Parcel, Status, Move>create()
                .onCall((GuardContext<Parcel, Status, Move> context) -> {
                    if (context.entity().id().equals("first")) {
                        firstEntered.countDown();
                        awaitLatch(releaseFirst, "first side effect was not released");
                    }
                });
        ControlledAsyncAction<Parcel, Status, Move> send = ControlledAsyncAction.create();
        MachineRegistry registry = gatedRegistry(gate, send);
        try (registry; gate; send) {
            Parcel first = new Parcel("first", Status.NEW);
            Parcel second = new Parcel("second", Status.NEW);
            CompletableFuture<Outcome<Parcel, Status, Move>> firstResult =
                    registry.of(PARCEL).async().execute(first, Move.SEND);
            try {
                awaitLatch(firstEntered, "first side effect was not entered");
                CompletableFuture<Outcome<Parcel, Status, Move>> secondResult =
                        registry.of(PARCEL).async().execute(second, Move.SEND);
                PendingInvocation<GuardCall<Parcel, Status, Move>, GuardResult> secondCall =
                        gate.awaitInvocation(GENEROUS);

                assertSame(second, secondCall.call().entity());
                List<PendingInvocation<GuardCall<Parcel, Status, Move>, GuardResult>> history = gate.invocations();
                assertEquals(2, history.size());
                assertSame(first, history.get(0).call().entity());
                assertSame(second, history.get(1).call().entity());
                assertTrue(secondCall.complete(GuardResult.reject("closed for the day")));
                AsyncAssertions.await(secondResult, GENEROUS).isRejected().hasEntitySameAs(second);
            } finally {
                releaseFirst.countDown();
            }

            PendingInvocation<GuardCall<Parcel, Status, Move>, GuardResult> firstCall =
                    gate.awaitInvocation(GENEROUS);
            assertSame(first, firstCall.call().entity());
            assertTrue(firstCall.complete(GuardResult.reject("closed for the day")));
            AsyncAssertions.await(firstResult, GENEROUS).isRejected().hasEntitySameAs(first);
        }
    }

    @Test
    void closingTheGuardFixtureCancelsItsPendingInvocations() {
        ControlledAsyncGuard<Parcel, Status, Move> gate = ControlledAsyncGuard.create();
        ControlledAsyncAction<Parcel, Status, Move> send = ControlledAsyncAction.create();
        MachineRegistry registry = gatedRegistry(gate, send);
        try (registry; send) {
            Parcel parcel = new Parcel("p", Status.NEW);
            CompletableFuture<Outcome<Parcel, Status, Move>> result =
                    registry.of(PARCEL).async().execute(parcel, Move.SEND);
            PendingInvocation<GuardCall<Parcel, Status, Move>, GuardResult> call = gate.awaitInvocation(GENEROUS);
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
        ControlledAsyncGuard<Parcel, Status, Move> gate = ControlledAsyncGuard.create();
        ControlledAsyncAction<Parcel, Status, Move> send = ControlledAsyncAction.create();
        gate.close();
        MachineRegistry registry = gatedRegistry(gate, send);
        try (registry; send) {
            Parcel parcel = new Parcel("p", Status.NEW);
            CompletableFuture<Outcome<Parcel, Status, Move>> result =
                    registry.of(PARCEL).async().execute(parcel, Move.SEND);
            AsyncAssertions.awaitFailure(result, GENEROUS).isAbort().hasCause(IllegalStateException.class);
            assertEquals(List.of(), gate.invocations());
            assertEquals(Status.NEW, parcel.status());
        }
    }

    @Test
    void aClosedGuardFixtureHandsOutNoHandleAndFailsItsWaitAtOnce() {
        CountDownLatch entered = new CountDownLatch(1);
        ControlledAsyncGuard<Parcel, Status, Move> gate = ControlledAsyncGuard.<Parcel, Status, Move>create()
                .onCall((GuardContext<Parcel, Status, Move> context) -> entered.countDown());
        ControlledAsyncAction<Parcel, Status, Move> send = ControlledAsyncAction.create();
        MachineRegistry registry = gatedRegistry(gate, send);
        try (registry; gate; send) {
            Parcel parcel = new Parcel("p", Status.NEW);
            CompletableFuture<Outcome<Parcel, Status, Move>> result =
                    registry.of(PARCEL).async().execute(parcel, Move.SEND);
            awaitLatch(entered, "the guard was not entered");
            gate.close();
            GraphAssertionError error =
                    assertThrows(GraphAssertionError.class, () -> gate.awaitInvocation(GENEROUS));
            assertEquals("controlled async guard", error.context());
            assertEquals("invocation", error.property());
            assertEquals("an open fixture", error.expected());
            assertEquals("closed", error.actual());
            AsyncAssertions.awaitFailure(result, GENEROUS).isAbort().hasCause(CancellationException.class);
        }
    }

    @Test
    void aGuardInvocationInItsSideEffectWhenTheFixtureClosesIsNotPublishedAfterwards() {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ControlledAsyncGuard<Parcel, Status, Move> gate = ControlledAsyncGuard.<Parcel, Status, Move>create()
                .onCall((GuardContext<Parcel, Status, Move> context) -> {
                    entered.countDown();
                    awaitLatch(release, "the side effect was not released");
                });
        ControlledAsyncAction<Parcel, Status, Move> send = ControlledAsyncAction.create();
        MachineRegistry registry = gatedRegistry(gate, send);
        try (registry; gate; send) {
            Parcel parcel = new Parcel("p", Status.NEW);
            CompletableFuture<Outcome<Parcel, Status, Move>> result =
                    registry.of(PARCEL).async().execute(parcel, Move.SEND);
            awaitLatch(entered, "the guard was not entered");
            gate.close();
            release.countDown();
            AsyncAssertions.awaitFailure(result, GENEROUS).isAbort().hasCause(CancellationException.class);
            GraphAssertionError error =
                    assertThrows(GraphAssertionError.class, () -> gate.awaitInvocation(BUDGET));
            assertEquals("closed", error.actual());
            assertEquals(1, gate.invocations().size());
            assertTrue(gate.invocations().get(0).isCancelled());
        }
    }

    @Test
    void closingTheGuardFixtureWakesAWaiterThatIsAlreadyBlocked() throws Exception {
        ControlledAsyncGuard<Parcel, Status, Move> gate = ControlledAsyncGuard.create();
        CompletableFuture<Throwable> ended = new CompletableFuture<>();
        Thread waiter = new Thread(() -> {
            try {
                gate.awaitInvocation(Duration.ofSeconds(30));
                ended.complete(null);
            } catch (RuntimeException | Error failure) {
                ended.complete(failure);
            }
        }, "guard-waiter");
        waiter.setDaemon(true);
        waiter.start();
        try {
            awaitBlocked(waiter);
            gate.close();
            GraphAssertionError error =
                    assertInstanceOf(GraphAssertionError.class, ended.get(2, TimeUnit.SECONDS));
            assertEquals("controlled async guard", error.context());
            assertEquals("invocation", error.property());
            assertEquals("an open fixture", error.expected());
            assertEquals("closed", error.actual());
        } finally {
            waiter.interrupt();
            waiter.join(2_000);
        }
    }

    @Test
    void aGuardSideEffectThatThrowsSettlesItsOwnInvocation() {
        IllegalStateException boom = new IllegalStateException("gate hook down");
        ControlledAsyncGuard<Parcel, Status, Move> gate = ControlledAsyncGuard.create();
        ControlledAsyncAction<Parcel, Status, Move> send = ControlledAsyncAction.create();
        gate.onCall(context -> {
            throw boom;
        });
        MachineRegistry registry = gatedRegistry(gate, send);
        try (registry; gate; send) {
            Parcel parcel = new Parcel("p", Status.NEW);
            CompletableFuture<Outcome<Parcel, Status, Move>> result =
                    registry.of(PARCEL).async().execute(parcel, Move.SEND);
            AsyncAssertions.awaitFailure(result, GENEROUS).isAbort().hasCauseSameAs(boom);
            List<PendingInvocation<GuardCall<Parcel, Status, Move>, GuardResult>> recorded = gate.invocations();
            assertEquals(1, recorded.size());
            assertTrue(recorded.get(0).isDone());
            assertFalse(recorded.get(0).complete(GuardResult.pass()));
            assertEquals(List.of(), send.invocations());
        }
    }

    // --- closing the control ---------------------------------------------------------------------------

    @Test
    void cancellationReturnsTrueOnlyToTheWinnerAndRejectsLaterAnswers() {
        ControlledAsyncAction<Entity, State, Id> action = ControlledAsyncAction.create();
        MachineRegistry registry = TestDomain.registry(TestDomain.graph(GENEROUS, action));
        try (registry; action) {
            Entity entity = new Entity("e", State.NEW);
            CompletableFuture<Outcome<Entity, State, Id>> result =
                    registry.of(KEY).async().execute(entity, Id.PREPARE);
            PendingInvocation<ActionCall<Entity, State, Id>, ActionOutcome> call = action.awaitInvocation(GENEROUS);

            assertTrue(call.cancelStage());
            assertFalse(call.cancelStage());
            action.close();
            assertFalse(call.cancelStage(), "cancellation after fixture close has no new winner");
            assertTrue(call.isCancelled());
            assertFalse(call.complete(ActionOutcome.ok()));
            assertFalse(call.fail(new IllegalStateException("late")));
            assertTrue(call.isCancelled());
            AsyncAssertions.awaitFailure(result, GENEROUS).isAbort().hasCause(CancellationException.class);
            assertEquals(State.NEW, entity.state());
        }
    }

    @Test
    void simultaneousCancellationHasExactlyOneWinner() throws Exception {
        ControlledAsyncAction<Entity, State, Id> action = ControlledAsyncAction.create();
        MachineRegistry registry = TestDomain.registry(TestDomain.graph(GENEROUS, action));
        try (registry; action) {
            Entity entity = new Entity("e", State.NEW);
            CompletableFuture<Outcome<Entity, State, Id>> result =
                    registry.of(KEY).async().execute(entity, Id.PREPARE);
            PendingInvocation<ActionCall<Entity, State, Id>, ActionOutcome> call = action.awaitInvocation(GENEROUS);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                Future<Boolean> first = workers.submit(() -> {
                    ready.countDown();
                    awaitLatch(start, "first cancellation worker did not start");
                    return call.cancelStage();
                });
                Future<Boolean> second = workers.submit(() -> {
                    ready.countDown();
                    awaitLatch(start, "second cancellation worker did not start");
                    return call.cancelStage();
                });
                awaitLatch(ready, "cancellation workers did not become ready");
                start.countDown();

                int winners = (first.get(2, TimeUnit.SECONDS) ? 1 : 0)
                        + (second.get(2, TimeUnit.SECONDS) ? 1 : 0);
                assertEquals(1, winners);
                assertTrue(call.isCancelled());
                assertFalse(call.complete(ActionOutcome.ok()));
                assertFalse(call.fail(new IllegalStateException("late")));
                assertTrue(call.isCancelled());
                AsyncAssertions.awaitFailure(result, GENEROUS).isAbort().hasCause(CancellationException.class);
                assertEquals(State.NEW, entity.state());
            } finally {
                start.countDown();
                workers.shutdownNow();
                if (!workers.awaitTermination(2, TimeUnit.SECONDS)) {
                    throw new AssertionError("cancellation workers did not terminate");
                }
            }
        }
    }

    @Test
    void closingCancelsPendingInvocationsAndIsSafeTwice() {
        ControlledAsyncAction<Entity, State, Id> action = ControlledAsyncAction.create();
        try (MachineRegistry registry = TestDomain.registry(TestDomain.graph(Duration.ofSeconds(30), action))) {
            Entity entity = new Entity("e", State.NEW);
            CompletableFuture<Outcome<Entity, State, Id>> result =
                    registry.of(KEY).async().execute(entity, Id.PREPARE);
            PendingInvocation<ActionCall<Entity, State, Id>, ActionOutcome> call = action.awaitInvocation(GENEROUS);
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
        ControlledAsyncAction<Entity, State, Id> action = ControlledAsyncAction.create();
        action.close();
        MachineRegistry registry = TestDomain.registry(TestDomain.graph(GENEROUS, action));
        try (registry; action) {
            Entity entity = new Entity("e", State.NEW);
            CompletableFuture<Outcome<Entity, State, Id>> result =
                    registry.of(KEY).async().execute(entity, Id.PREPARE);
            ExecutionException raised =
                    assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
            GraphAssertionError refusal = assertInstanceOf(GraphAssertionError.class, raised.getCause());
            assertEquals("controlled async action", refusal.context());
            assertEquals("invocation", refusal.property());
            assertEquals("an open fixture", refusal.expected());
            assertEquals("closed", refusal.actual());
            assertEquals(List.of(), action.invocations());
            assertEquals(State.NEW, entity.state());
        }
    }

    /**
     * A refusal that commits a state change is not a refusal. The edge below recovers from any
     * {@link IllegalStateException}, which is what the refusal used to be, so a test that executed through a
     * closed fixture would have passed on the recovery route; the refusal is an assertion failure now, and
     * the engine routes no {@link Error}.
     */
    @Test
    void aClosedActionFixtureIsNeverTakenForAFailureTheEdgeRecoversFrom() {
        ControlledAsyncAction<Entity, State, Id> action = ControlledAsyncAction.create();
        action.close();
        GraphDefinition<Entity, State, Id> graph = StateGraph.define(KEY).initial(State.NEW)
                .terminal(State.DONE).terminal(State.FAILED)
                .statePolicy(State.NEW, "prepare first").statePolicy(State.READY, "finish only")
                .transition(Id.PREPARE, State.NEW, State.READY).asyncAction("prepare", action)
                    .onFailure(Id.RECOVER).failsOn(IllegalStateException.class).endTransition()
                .transition(Id.RECOVER, State.NEW, State.FAILED).endTransition()
                .transition(Id.FINISH, State.READY, State.DONE).endTransition()
                .build();
        MachineRegistry registry = TestDomain.registry(graph);
        try (registry; action) {
            Entity entity = new Entity("e", State.NEW);
            CompletableFuture<Outcome<Entity, State, Id>> result =
                    registry.of(KEY).async().execute(entity, Id.PREPARE);
            ExecutionException raised =
                    assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
            assertInstanceOf(GraphAssertionError.class, raised.getCause());
            assertEquals(State.NEW, entity.state());
            assertEquals(List.of(), action.invocations());
        }
    }

    /**
     * Closing cancels the invocations the fixture holds, and a cancelled handle is nothing to answer any more:
     * the one that was queued or still being published when the fixture closed is not handed out afterwards,
     * and a wait on a closed fixture fails at once instead of after its whole budget.
     */
    @Test
    void aClosedActionFixtureHandsOutNoHandleAndFailsItsWaitAtOnce() {
        CountDownLatch entered = new CountDownLatch(1);
        ControlledAsyncAction<Entity, State, Id> action = ControlledAsyncAction.<Entity, State, Id>create()
                .onCall((TransitionContext<Entity, State, Id> context) -> entered.countDown());
        MachineRegistry registry = TestDomain.registry(TestDomain.graph(Duration.ofSeconds(30), action));
        try (registry; action) {
            Entity entity = new Entity("e", State.NEW);
            CompletableFuture<Outcome<Entity, State, Id>> result =
                    registry.of(KEY).async().execute(entity, Id.PREPARE);
            awaitLatch(entered, "the action was not entered");
            action.close();
            GraphAssertionError error =
                    assertThrows(GraphAssertionError.class, () -> action.awaitInvocation(GENEROUS));
            assertEquals("controlled async action", error.context());
            assertEquals("invocation", error.property());
            assertEquals("an open fixture", error.expected());
            assertEquals("closed", error.actual());
            AsyncAssertions.awaitFailure(result, GENEROUS).isAbort().hasCause(CancellationException.class);
        }
    }

    @Test
    void anActionInvocationInItsSideEffectWhenTheFixtureClosesIsNotPublishedAfterwards() {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ControlledAsyncAction<Entity, State, Id> action = ControlledAsyncAction.<Entity, State, Id>create()
                .onCall((TransitionContext<Entity, State, Id> context) -> {
                    entered.countDown();
                    awaitLatch(release, "the side effect was not released");
                });
        MachineRegistry registry = TestDomain.registry(TestDomain.graph(Duration.ofSeconds(30), action));
        try (registry; action) {
            Entity entity = new Entity("e", State.NEW);
            CompletableFuture<Outcome<Entity, State, Id>> result =
                    registry.of(KEY).async().execute(entity, Id.PREPARE);
            awaitLatch(entered, "the action was not entered");
            action.close();
            release.countDown();
            // The engine holds the stage only once the action returned it, which is after it was published or
            // refused, so the history below is final when the failure arrives.
            AsyncAssertions.awaitFailure(result, GENEROUS).isAbort().hasCause(CancellationException.class);
            GraphAssertionError error =
                    assertThrows(GraphAssertionError.class, () -> action.awaitInvocation(BUDGET));
            assertEquals("closed", error.actual());
            assertEquals(1, action.invocations().size());
            assertTrue(action.invocations().get(0).isCancelled());
        }
    }

    /**
     * A wait that is already blocked when the fixture closes is woken by the close and fails like any wait on a
     * closed fixture, instead of sitting out its whole budget and then reporting that nothing arrived.
     */
    @Test
    void closingTheActionFixtureWakesAWaiterThatIsAlreadyBlocked() throws Exception {
        ControlledAsyncAction<Entity, State, Id> action = ControlledAsyncAction.create();
        CompletableFuture<Throwable> ended = new CompletableFuture<>();
        Thread waiter = new Thread(() -> {
            try {
                action.awaitInvocation(Duration.ofSeconds(30));
                ended.complete(null);
            } catch (RuntimeException | Error failure) {
                ended.complete(failure);
            }
        }, "action-waiter");
        waiter.setDaemon(true);
        waiter.start();
        try {
            awaitBlocked(waiter);
            action.close();
            GraphAssertionError error =
                    assertInstanceOf(GraphAssertionError.class, ended.get(2, TimeUnit.SECONDS));
            assertEquals("controlled async action", error.context());
            assertEquals("invocation", error.property());
            assertEquals("an open fixture", error.expected());
            assertEquals("closed", error.actual());
        } finally {
            waiter.interrupt();
            waiter.join(2_000);
        }
    }

    // --- a side effect that throws must not leave a stage nobody will answer ---------------------------

    @Test
    void anOnCallThatThrowsSettlesItsOwnInvocationAndPublishesNoHandle() {
        IllegalStateException boom = new IllegalStateException("hook down");
        ControlledAsyncAction<Entity, State, Id> action = ControlledAsyncAction.<Entity, State, Id>create()
                .onCall(context -> {
                    throw boom;
                });
        MachineRegistry registry = TestDomain.registry(TestDomain.graph(GENEROUS, action));
        try (registry; action) {
            Entity entity = new Entity("e", State.NEW);
            CompletableFuture<Outcome<Entity, State, Id>> result =
                    registry.of(KEY).async().execute(entity, Id.PREPARE);
            AsyncAssertions.awaitFailure(result, GENEROUS).isAbort().hasCauseSameAs(boom);
            List<PendingInvocation<ActionCall<Entity, State, Id>, ActionOutcome>> recorded = action.invocations();
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
        ControlledAsyncAction<Entity, State, Id> action = ControlledAsyncAction.<Entity, State, Id>create()
                .onCall(context -> {
                    throw boom;
                });
        MachineRegistry registry = TestDomain.registry(TestDomain.graph(GENEROUS, action));
        try (registry; action) {
            Entity entity = new Entity("e", State.NEW);
            CompletableFuture<Outcome<Entity, State, Id>> result =
                    registry.of(KEY).async().execute(entity, Id.PREPARE);
            ExecutionException raised =
                    assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
            assertSame(boom, raised.getCause());
            List<PendingInvocation<ActionCall<Entity, State, Id>, ActionOutcome>> recorded = action.invocations();
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
        ControlledAsyncGuard<Parcel, Status, Move> gate = ControlledAsyncGuard.create();
        ControlledAsyncAction<Parcel, Status, Move> send = ControlledAsyncAction.create();
        gate.onCall(context -> {
            throw boom;
        });
        MachineRegistry registry = gatedRegistry(gate, send);
        try (registry; gate; send) {
            Parcel parcel = new Parcel("p", Status.NEW);
            CompletableFuture<Outcome<Parcel, Status, Move>> result =
                    registry.of(PARCEL).async().execute(parcel, Move.SEND);
            ExecutionException raised =
                    assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
            assertSame(boom, raised.getCause());
            List<PendingInvocation<GuardCall<Parcel, Status, Move>, GuardResult>> recorded = gate.invocations();
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
        ControlledAsyncAction<Entity, State, Id> action = ControlledAsyncAction.create();
        CompletableFuture<Void> release = new CompletableFuture<>();
        CompletableFuture<String> seen = new CompletableFuture<>();
        action.onCall(context -> {
            seen.complete(context.entity().id() + ":" + context.from() + ">" + context.to());
            release.join();
        });
        MachineRegistry registry = TestDomain.registry(TestDomain.graph(Duration.ofSeconds(30), action));
        try (registry; action) {
            Entity entity = new Entity("e", State.NEW);
            CompletableFuture<Outcome<Entity, State, Id>> result =
                    registry.of(KEY).async().execute(entity, Id.PREPARE);
            try {
                assertEquals("e:NEW>READY", seen.get(2, TimeUnit.SECONDS),
                        "the side effect reads the live context");
                assertThrows(GraphAssertionError.class, () -> action.awaitInvocation(BUDGET),
                        "no handle while the side effect is still running");
            } finally {
                release.complete(null);
            }
            PendingInvocation<ActionCall<Entity, State, Id>, ActionOutcome> call = action.awaitInvocation(GENEROUS);
            assertTrue(call.complete(ActionOutcome.ok()));
            AsyncAssertions.await(result, GENEROUS).isApplied().movedTo(State.READY);
        }
    }

    @Test
    void deliveryFollowsPublicationAfterSuccessfulSideEffectsWhileHistoryKeepsEntryOrder() {
        ControlledAsyncAction<Entity, State, Id> action = ControlledAsyncAction.create();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        action.onCall(context -> {
            if (context.entity().id().equals("first")) {
                firstEntered.countDown();
                awaitLatch(releaseFirst, "first side effect was not released");
            }
        });
        MachineRegistry registry = TestDomain.registry(TestDomain.graph(Duration.ofSeconds(30), action));
        try (registry; action) {
            Entity first = new Entity("first", State.NEW);
            Entity second = new Entity("second", State.NEW);
            CompletableFuture<Outcome<Entity, State, Id>> firstResult =
                    registry.of(KEY).async().execute(first, Id.PREPARE);
            PendingInvocation<ActionCall<Entity, State, Id>, ActionOutcome> secondCall;
            try {
                awaitLatch(firstEntered, "first side effect was not entered");
                CompletableFuture<Outcome<Entity, State, Id>> secondResult =
                        registry.of(KEY).async().execute(second, Id.PREPARE);
                secondCall = action.awaitInvocation(GENEROUS);

                assertSame(second, secondCall.call().entity());
                assertEquals(List.of("first", "second"), action.invocations().stream()
                        .map(invocation -> invocation.call().entity().id())
                        .toList());
                assertTrue(secondCall.complete(ActionOutcome.ok()));
                AsyncAssertions.await(secondResult, GENEROUS).isApplied().hasEntitySameAs(second);
            } finally {
                releaseFirst.countDown();
            }

            PendingInvocation<ActionCall<Entity, State, Id>, ActionOutcome> firstCall =
                    action.awaitInvocation(GENEROUS);
            assertSame(first, firstCall.call().entity());
            assertTrue(firstCall.complete(ActionOutcome.ok()));
            AsyncAssertions.await(firstResult, GENEROUS).isApplied().hasEntitySameAs(first);
        }
    }

    // --- the fixture's own wait is the test's budget, not the machine's --------------------------------

    @Test
    void awaitInvocationFailsAsAnAssertionWhenNoInvocationArrives() {
        ControlledAsyncAction<Entity, State, Id> action = ControlledAsyncAction.create();
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
        ControlledAsyncGuard<Parcel, Status, Move> gate = ControlledAsyncGuard.create();
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
        ControlledAsyncAction<Entity, State, Id> action = ControlledAsyncAction.create();
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
        ControlledAsyncGuard<Parcel, Status, Move> gate = ControlledAsyncGuard.create();
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
        ControlledAsyncAction<Entity, State, Id> action = ControlledAsyncAction.create();
        ControlledAsyncGuard<Parcel, Status, Move> gate = ControlledAsyncGuard.create();
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
        ControlledAsyncAction<Entity, State, Id> action = ControlledAsyncAction.create();
        MachineRegistry registry = TestDomain.registry(TestDomain.graph(GENEROUS, action));
        try (registry; action) {
            Entity entity = new Entity("e", State.NEW);
            registry.of(KEY).async().execute(entity, Id.PREPARE);
            PendingInvocation<ActionCall<Entity, State, Id>, ActionOutcome> call = action.awaitInvocation(GENEROUS);
            assertThrows(NullPointerException.class, () -> call.complete(null));
            assertThrows(NullPointerException.class, () -> call.fail(null));
            assertFalse(call.isDone());
            assertTrue(call.complete(ActionOutcome.ok()));
        }
    }

    @Test
    void aFailedInvocationReachesTheEngineAsTheCauseOfTheAbort() {
        ControlledAsyncAction<Entity, State, Id> action = ControlledAsyncAction.create();
        IllegalStateException boom = new IllegalStateException("remote call refused");
        MachineRegistry registry = TestDomain.registry(TestDomain.graph(GENEROUS, action));
        try (registry; action) {
            Entity entity = new Entity("e", State.NEW);
            CompletableFuture<Outcome<Entity, State, Id>> result =
                    registry.of(KEY).async().execute(entity, Id.PREPARE);
            PendingInvocation<ActionCall<Entity, State, Id>, ActionOutcome> call = action.awaitInvocation(GENEROUS);
            assertTrue(call.fail(boom));
            assertFalse(call.fail(boom));
            assertTrue(call.isDone());
            assertFalse(call.isCancelled());
            AsyncAssertions.awaitFailure(result, GENEROUS).isAbort().hasCauseSameAs(boom);
            assertEquals(State.NEW, entity.state());
        }
    }

    // --- typed recovery: the handle hands the engine the very answer the test gave ---------------------

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aFailedAnswerWithAFailureKindTakesTheRuleOfThatKind() {
        ControlledAsyncAction<Ticket, TicketState, TicketChange> publish = ControlledAsyncAction.create();
        MachineRegistry registry = PublicationFixture.registry(PublicationFixture.asyncGraph(publish));
        try (registry; publish) {
            CompletableFuture<Outcome<Ticket, TicketState, TicketChange>> result = null;
            try {
                Ticket ticket = new Ticket("t");
                result =
                        registry.of(PublicationFixture.KEY).async().execute(ticket, TicketChange.PUBLISH);
                PendingInvocation<ActionCall<Ticket, TicketState, TicketChange>, ActionOutcome> call =
                        publish.awaitInvocation(GENEROUS);
                assertTrue(call.complete(ActionOutcome.failed(PublicationFailures.Rejected.class, "content rejected")));
                AsyncAssertions.await(result, GENEROUS).isFailed()
                        .hasFailureKind(PublicationFailures.Rejected.class)
                        .hasFailureTransition(TicketChange.RECORD_REJECTION)
                        .movedTo(TicketState.REJECTED_BY_PROVIDER)
                        .hasReason("content rejected")
                        .hasNoCause();
                assertEquals(TicketState.REJECTED_BY_PROVIDER, ticket.state());
            } finally {
                if (result != null) {
                    result.cancel(true);
                }
                publish.close();
            }
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aFailedInvocationWithAnExceptionARuleListsKeepsItsKindAndItsIdentity() {
        ServiceUnavailableException down = new ServiceUnavailableException("provider down");
        ControlledAsyncAction<Ticket, TicketState, TicketChange> publish = ControlledAsyncAction.create();
        MachineRegistry registry = PublicationFixture.registry(PublicationFixture.asyncGraph(publish));
        try (registry; publish) {
            CompletableFuture<Outcome<Ticket, TicketState, TicketChange>> result = null;
            try {
                Ticket ticket = new Ticket("t");
                result =
                        registry.of(PublicationFixture.KEY).async().execute(ticket, TicketChange.PUBLISH);
                PendingInvocation<ActionCall<Ticket, TicketState, TicketChange>, ActionOutcome> call =
                        publish.awaitInvocation(GENEROUS);
                assertTrue(call.fail(down));
                AsyncAssertions.await(result, GENEROUS).isFailed()
                        .hasFailureKind(PublicationFailures.Unavailable.class)
                        .hasFailureTransition(TicketChange.SCHEDULE_RETRY)
                        .movedTo(TicketState.RETRY_PENDING)
                        .hasCauseSameAs(down);
                assertEquals(TicketState.RETRY_PENDING, ticket.state());
            } finally {
                if (result != null) {
                    result.cancel(true);
                }
                publish.close();
            }
        }
    }

    /**
     * Each invocation owns its stage, so answering the second one first settles that call alone: each outcome keeps
     * the failure kind, the recovery and the cause of the answer given to its own invocation.
     */
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void twoInvocationsAnsweredInReverseOrderKeepTheirOwnFailureKinds() {
        ProviderRejectedException refused = new ProviderRejectedException("content refused");
        ControlledAsyncAction<Ticket, TicketState, TicketChange> publish = ControlledAsyncAction.create();
        MachineRegistry registry = PublicationFixture.registry(PublicationFixture.asyncGraph(publish));
        try (registry; publish) {
            CompletableFuture<Outcome<Ticket, TicketState, TicketChange>> firstResult = null;
            CompletableFuture<Outcome<Ticket, TicketState, TicketChange>> secondResult = null;
            try {
                Ticket first = new Ticket("first");
                Ticket second = new Ticket("second");
                firstResult =
                        registry.of(PublicationFixture.KEY).async().execute(first, TicketChange.PUBLISH);
                secondResult =
                        registry.of(PublicationFixture.KEY).async().execute(second, TicketChange.PUBLISH);
                PendingInvocation<ActionCall<Ticket, TicketState, TicketChange>, ActionOutcome> one =
                        publish.awaitInvocation(GENEROUS);
                PendingInvocation<ActionCall<Ticket, TicketState, TicketChange>, ActionOutcome> other =
                        publish.awaitInvocation(GENEROUS);
                PendingInvocation<ActionCall<Ticket, TicketState, TicketChange>, ActionOutcome> firstCall =
                        one.call().entity() == first ? one : other;
                PendingInvocation<ActionCall<Ticket, TicketState, TicketChange>, ActionOutcome> secondCall =
                        firstCall == one ? other : one;
                assertSame(first, firstCall.call().entity());
                assertSame(second, secondCall.call().entity());

                assertTrue(secondCall.complete(
                        ActionOutcome.failed(PublicationFailures.Unavailable.class, "provider busy")));
                AsyncAssertions.await(secondResult, GENEROUS).isFailed()
                        .hasEntitySameAs(second)
                        .hasFailureKind(PublicationFailures.Unavailable.class)
                        .hasFailureTransition(TicketChange.SCHEDULE_RETRY)
                        .movedTo(TicketState.RETRY_PENDING)
                        .hasNoCause();
                assertFalse(firstResult.isDone());
                assertEquals(TicketState.READY, first.state());

                assertTrue(firstCall.fail(refused));
                AsyncAssertions.await(firstResult, GENEROUS).isFailed()
                        .hasEntitySameAs(first)
                        .hasFailureKind(PublicationFailures.Rejected.class)
                        .hasFailureTransition(TicketChange.RECORD_REJECTION)
                        .movedTo(TicketState.REJECTED_BY_PROVIDER)
                        .hasCauseSameAs(refused);
                assertEquals(TicketState.REJECTED_BY_PROVIDER, first.state());
                assertEquals(TicketState.RETRY_PENDING, second.state());
            } finally {
                if (firstResult != null) {
                    firstResult.cancel(true);
                }
                if (secondResult != null) {
                    secondResult.cancel(true);
                }
                publish.close();
            }
        }
    }

    /** Waits until the thread sits in a timed wait, which is where a blocked {@code awaitInvocation} is. */
    private static void awaitBlocked(Thread thread) {
        long start = System.nanoTime();
        while (thread.getState() != Thread.State.TIMED_WAITING) {
            if (System.nanoTime() - start > TimeUnit.SECONDS.toNanos(2)) {
                throw new AssertionError("the waiter never blocked, it is " + thread.getState());
            }
            Thread.onSpinWait();
        }
    }

    private static void awaitLatch(CountDownLatch latch, String timeoutMessage) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError(timeoutMessage);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("test thread interrupted while waiting on a latch", interrupted);
        }
    }

    private static MachineRegistry gatedRegistry(AsyncTransitionGuard<Parcel, Status, Move> guard,
                                                 AsyncTransitionAction<Parcel, Status, Move> action) {
        GraphDefinition<Parcel, Status, Move> graph = StateGraph.define(PARCEL)
                .initial(Status.NEW).terminal(Status.SENT)
                .statePolicy(Status.NEW, "only sending")
                .transition(Move.SEND, Status.NEW, Status.SENT)
                    .asyncAction("send", action).asyncGuard("gate", guard).endTransition()
                .build();
        return MachineRegistry.builder()
                .add(StateMachines.from(graph).state(Parcel::status, Parcel::setStatus)
                        .identity(Parcel::id).asyncTimeout(Duration.ofSeconds(5)))
                .build();
    }

    /** States of the parcel machine: a parcel stays {@code NEW} until the gated send moves it to {@code SENT}. */
    private enum Status { NEW, SENT }

    /** Transition identifiers of the parcel machine. */
    private enum Move { SEND }

    /** Entity of the parcel machine: an id and a mutable {@link Status}. */
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
