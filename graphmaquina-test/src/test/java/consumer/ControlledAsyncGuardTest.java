package consumer;

import static consumer.BoundedWaits.awaitBlocked;
import static consumer.BoundedWaits.awaitLatch;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.AsyncTransitionAction;
import io.github.graphmaquina.api.AsyncTransitionGuard;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.runtime.MachineRegistry;
import io.github.graphmaquina.runtime.StateMachines;
import io.github.graphmaquina.test.AsyncAssertions;
import io.github.graphmaquina.test.GraphAssertionError;
import io.github.graphmaquina.test.fixtures.TestDomain.Entity;
import io.github.graphmaquina.test.fixtures.TestDomain.Id;
import io.github.graphmaquina.test.fixtures.TestDomain.State;
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
import java.util.concurrent.TimeUnit;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.junit.jupiter.api.Test;

/**
 * Contract of the controlled asynchronous guard fixture; {@link ControlledAsyncTest} holds the action's, except for
 * the budget check that exercises both fixtures and sits here because the parcel types are private to this class.
 * Nothing here sleeps: the test resumes when the engine has entered the guard, and the engine resumes when the test
 * answers that very invocation. A mismatch is read off the fields of the {@link GraphAssertionError}, not off its
 * type.
 */
class ControlledAsyncGuardTest {

    private static final MachineKey<Parcel, Status, Move> PARCEL =
            MachineKey.of(Parcel.class, Status.class, Move.class);
    private static final Duration BUDGET = Duration.ofMillis(50);
    private static final Duration GENEROUS = Duration.ofSeconds(2);

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

    // --- a side effect that throws must not leave a stage nobody will answer ---------------------------

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

    // --- the fixture's own wait is the test's budget, not the machine's --------------------------------

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
    @Getter(AccessLevel.PACKAGE)
    @AllArgsConstructor(access = AccessLevel.PACKAGE)
    private static final class Parcel {

        private final String id;
        private Status status;

        void setStatus(Status status) {
            this.status = status;
        }
    }
}
