package consumer;

import static io.github.graphmaquina.test.TransitionFailureAssert.captureFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.runtime.MachineRegistry;
import io.github.graphmaquina.runtime.StateMachines;
import io.github.graphmaquina.test.GraphAssertionError;
import io.github.graphmaquina.test.TransitionFailureAssert;
import io.github.graphmaquina.test.fixtures.TestDomain.Entity;
import io.github.graphmaquina.test.fixtures.TestDomain.Id;
import io.github.graphmaquina.test.fixtures.TestDomain.State;
import io.github.graphmaquina.test.probe.StateWrite;
import io.github.graphmaquina.test.probe.StateWriteProbe;
import io.github.graphmaquina.test.probe.WriteStatus;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;

/**
 * Contract of the state-write probe. It stands where the machine's own setter stands, so it sees phase 2 as
 * the engine runs it: one call per entity, in the order the engine wrote them. An armed call throws before
 * the wrapped setter is reached, which is what leaves the entity where it was and lets a test watch a commit
 * fail partway; every mismatch is read off the fields of the {@link GraphAssertionError}, not off its type.
 */
class StateWriteProbeTest {

    private static final MachineKey<Order, State, Id> ORDER =
            MachineKey.of(Order.class, State.class, Id.class);
    private static final MachineKey<Item, State, Id> ITEM =
            MachineKey.of(Item.class, State.class, Id.class);

    // --- the premise of this task: an armed call never reaches the setter -----------------------------

    @Test
    void scheduledFailureDoesNotInvokeTheWrappedSetter() {
        Entity entity = new Entity("e", State.NEW);
        IllegalStateException boom = new IllegalStateException("setter down");
        StateWriteProbe<Entity, State> writes =
                StateWriteProbe.<Entity, State>wrapping(Entity::setState).failOnCall(2, boom);
        writes.accept(entity, State.READY);
        assertSame(boom,
                assertThrows(IllegalStateException.class, () -> writes.accept(entity, State.DONE)));
        assertEquals(State.READY, entity.state());
        assertEquals(List.of(WriteStatus.RETURNED, WriteStatus.THREW),
                writes.writes().stream().map(StateWrite::status).toList());
    }

    @Test
    void onlyTheArmedCallIsFailedAndEveryCallIsRecordedAsItWasEntered() {
        IllegalStateException boom = new IllegalStateException("setter down");
        List<String> delegated = new ArrayList<>();
        StateWriteProbe<Entity, State> writes = StateWriteProbe
                .<Entity, State>wrapping((Entity entity, State state) -> delegated.add(entity.id() + ":" + state))
                .failOnCall(2, boom);
        Entity first = new Entity("first", State.NEW);
        Entity second = new Entity("second", State.NEW);
        Entity third = new Entity("third", State.NEW);
        writes.accept(first, State.READY);
        assertSame(boom, assertThrows(IllegalStateException.class, () -> writes.accept(second, State.DONE)));
        writes.accept(third, State.FAILED);
        assertEquals(List.of("first:READY", "third:FAILED"), delegated);
        List<StateWrite<Entity, State>> recorded = writes.writes();
        assertEquals(List.of(first, second, third), recorded.stream().map(StateWrite::entity).toList());
        assertEquals(List.of(State.READY, State.DONE, State.FAILED),
                recorded.stream().map(StateWrite::requested).toList());
        assertEquals(List.of(WriteStatus.RETURNED, WriteStatus.THREW, WriteStatus.RETURNED),
                recorded.stream().map(StateWrite::status).toList());
        assertEquals(Optional.empty(), recorded.get(0).cause());
        assertSame(boom, recorded.get(1).cause().orElseThrow());
        assertEquals(Optional.empty(), recorded.get(2).cause());
    }

    @Test
    void aWrappedSetterThatThrowsKeepsItsOwnExceptionAndIsRecordedAsThrown() {
        IllegalStateException boom = new IllegalStateException("the column is gone");
        StateWriteProbe<Entity, State> writes =
                StateWriteProbe.<Entity, State>wrapping((Entity entity, State state) -> { throw boom; });
        Entity entity = new Entity("e", State.NEW);
        assertSame(boom,
                assertThrows(IllegalStateException.class, () -> writes.accept(entity, State.READY)));
        StateWrite<Entity, State> write = writes.writes().get(0);
        assertEquals(WriteStatus.THREW, write.status());
        assertSame(boom, write.cause().orElseThrow());
        assertSame(entity, write.entity());
        assertEquals(State.READY, write.requested());
        assertEquals(State.NEW, entity.state());
    }

    @Test
    void writesKeepTheirEntryOrderEvenWhenTheCallsFinishOutOfOrder() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<State> delegated = Collections.synchronizedList(new ArrayList<>());
        StateWriteProbe<Entity, State> writes =
                StateWriteProbe.<Entity, State>wrapping((Entity entity, State state) -> {
                    if (state == State.READY) {
                        entered.countDown();
                        awaitQuietly(release);
                        delegated.add(state);
                    } else {
                        delegated.add(state);
                        release.countDown();
                    }
                });
        Entity slow = new Entity("slow", State.NEW);
        Entity fast = new Entity("fast", State.NEW);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> blocked = pool.submit(() -> {
                writes.accept(slow, State.READY);
                return null;
            });
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            writes.accept(fast, State.DONE);
            blocked.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(List.of(State.DONE, State.READY), delegated);
        assertEquals(List.of(slow, fast), writes.writes().stream().map(StateWrite::entity).toList());
    }

    @Test
    void aCallStillInsideTheSetterIsRecordedAsRunningAndKeepsItsPlace() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        StateWriteProbe<Entity, State> writes =
                StateWriteProbe.<Entity, State>wrapping((Entity entity, State state) -> {
                    if (state == State.READY) {
                        entered.countDown();
                        awaitQuietly(release);
                    }
                });
        Entity slow = new Entity("slow", State.NEW);
        Entity fast = new Entity("fast", State.NEW);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> blocked = pool.submit(() -> {
                writes.accept(slow, State.READY);
                return null;
            });
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            writes.assertCount(1);
            StateWrite<Entity, State> running = writes.writes().get(0);
            assertSame(slow, running.entity());
            assertEquals(State.READY, running.requested());
            assertEquals(WriteStatus.RUNNING, running.status());
            assertEquals(Optional.empty(), running.cause());
            writes.accept(fast, State.DONE);
            writes.assertCount(2);
            assertEquals(List.of(slow, fast), writes.writes().stream().map(StateWrite::entity).toList());
            assertEquals(List.of(WriteStatus.RUNNING, WriteStatus.RETURNED),
                    writes.writes().stream().map(StateWrite::status).toList());
            release.countDown();
            blocked.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertEquals(List.of(WriteStatus.RETURNED, WriteStatus.RETURNED),
                writes.writes().stream().map(StateWrite::status).toList());
    }

    @Test
    void writesIsAnImmutableCopyTakenWhenItWasAsked() {
        StateWriteProbe<Entity, State> writes = StateWriteProbe.<Entity, State>wrapping(Entity::setState);
        Entity first = new Entity("first", State.NEW);
        Entity second = new Entity("second", State.NEW);
        writes.accept(first, State.READY);
        List<StateWrite<Entity, State>> taken = writes.writes();
        writes.accept(second, State.READY);
        assertEquals(List.of(first), taken.stream().map(StateWrite::entity).toList());
        assertEquals(List.of(first, second), writes.writes().stream().map(StateWrite::entity).toList());
        assertThrows(UnsupportedOperationException.class, taken::clear);
    }

    // --- counters and their failures -------------------------------------------------------------------

    @Test
    void assertCountReportsTheCountItSaw() {
        StateWriteProbe<Entity, State> writes = StateWriteProbe.<Entity, State>wrapping(Entity::setState);
        writes.accept(new Entity("e", State.NEW), State.READY);
        assertSame(writes, writes.assertCount(1));
        GraphAssertionError error = assertThrows(GraphAssertionError.class, () -> writes.assertCount(3));
        assertEquals("state writes", error.context());
        assertEquals("count", error.property());
        assertEquals(Integer.valueOf(3), error.expected());
        assertEquals(Integer.valueOf(1), error.actual());
    }

    // --- fixture configuration errors ------------------------------------------------------------------

    @Test
    void theProbeRejectsAMissingDelegateOrAnImpossibleOrdinal() {
        assertThrows(NullPointerException.class, () -> StateWriteProbe.<Entity, State>wrapping(null));
        StateWriteProbe<Entity, State> writes = StateWriteProbe.<Entity, State>wrapping(Entity::setState);
        assertThrows(NullPointerException.class, () -> writes.failOnCall(1, null));
        assertThrows(IllegalArgumentException.class,
                () -> writes.failOnCall(0, new IllegalStateException("boom")));
        assertThrows(IllegalArgumentException.class,
                () -> writes.failOnCall(-1, new IllegalStateException("boom")));
    }

    @Test
    void aRecordedWriteRejectsEveryNullComponent() {
        Entity entity = new Entity("e", State.NEW);
        assertEquals("entity", assertThrows(NullPointerException.class, () -> new StateWrite<Entity, State>(
                null, State.READY, WriteStatus.RETURNED, Optional.empty())).getMessage());
        assertEquals("requested", assertThrows(NullPointerException.class, () -> new StateWrite<Entity, State>(
                entity, null, WriteStatus.RETURNED, Optional.empty())).getMessage());
        assertEquals("status", assertThrows(NullPointerException.class, () -> new StateWrite<Entity, State>(
                entity, State.READY, null, Optional.empty())).getMessage());
        assertEquals("cause", assertThrows(NullPointerException.class, () -> new StateWrite<Entity, State>(
                entity, State.READY, WriteStatus.RETURNED, null)).getMessage());
    }

    @Test
    void armingTheSameCallTwiceIsAFixtureError() {
        IllegalStateException armed = new IllegalStateException("setter down");
        StateWriteProbe<Entity, State> writes =
                StateWriteProbe.<Entity, State>wrapping(Entity::setState).failOnCall(2, armed);
        assertThrows(IllegalStateException.class,
                () -> writes.failOnCall(2, new IllegalStateException("something else")));
        Entity entity = new Entity("e", State.NEW);
        writes.accept(entity, State.READY);
        assertSame(armed, assertThrows(IllegalStateException.class, () -> writes.accept(entity, State.DONE)));
    }

    // --- against the real engine -----------------------------------------------------------------------

    @Test
    void aSetterThatFailsPartwayLeavesTheRootAppliedNamesTheFailedPeerAndLeavesTheOtherPending() {
        Order order = new Order("order");
        Item first = new Item("first");
        Item second = new Item("second");
        IllegalStateException boom = new IllegalStateException("the connection is gone");
        StateWriteProbe<Tracked, State> writes =
                StateWriteProbe.<Tracked, State>wrapping(Tracked::setState).failOnCall(2, boom);
        Throwable failure = captureFailure(() -> {
            try (MachineRegistry registry = registry(writes, first, second)) {
                registry.of(ORDER).execute(order, Id.PREPARE);
            }
        });
        TransitionFailureAssert.assertThat(failure).isCommitFailure()
                .hasAppliedExactly(order)
                .hasFailedEntity(first)
                .hasPendingExactly(second)
                .hasCauseSameAs(boom);
        assertEquals(State.READY, order.state());
        assertEquals(State.NEW, first.state());
        assertEquals(State.NEW, second.state());
        writes.assertCount(2);
        assertEquals(List.of(order, first), writes.writes().stream().map(StateWrite::entity).toList());
        assertEquals(List.of(WriteStatus.RETURNED, WriteStatus.THREW),
                writes.writes().stream().map(StateWrite::status).toList());
    }

    @Test
    void anAbortBeforeTheCommitNeverReachesTheSetter() {
        Order order = new Order("order");
        Item first = new Item("first");
        Item second = new Item("second");
        IllegalStateException boom = new IllegalStateException("no stock");
        StateWriteProbe<Tracked, State> writes = StateWriteProbe.<Tracked, State>wrapping(Tracked::setState);
        TransitionAbortedException aborted = assertThrows(TransitionAbortedException.class, () -> {
            try (MachineRegistry registry = registry(writes,
                    (TransitionContext<Order, State, Id> context) -> { throw boom; })) {
                registry.of(ORDER).execute(order, Id.PREPARE);
            }
        });
        assertSame(boom, aborted.getCause());
        writes.assertCount(0);
        assertEquals(List.of(), writes.writes());
        assertEquals(State.NEW, order.state());
        assertEquals(State.NEW, first.state());
        assertEquals(State.NEW, second.state());
    }

    // --- fixtures ---------------------------------------------------------------------------------------

    /** What both machines of the integration fixture have in common: one state, written through the probe. */
    private interface Tracked {

        String id();

        State state();

        void setState(State state);
    }

    /** Root of the cascade: its action moves both of its items before anything is committed. */
    private static final class Order implements Tracked {

        private final String id;
        private State state = State.NEW;

        Order(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public State state() {
            return state;
        }

        @Override
        public void setState(State state) {
            this.state = state;
        }
    }

    /** Peer of the cascade, moved from the order's action and written after it in phase 2. */
    private static final class Item implements Tracked {

        private final String id;
        private State state = State.NEW;

        Item(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public State state() {
            return state;
        }

        @Override
        public void setState(State state) {
            this.state = state;
        }
    }

    private static MachineRegistry registry(BiConsumer<? super Tracked, ? super State> setter,
            Item first, Item second) {
        return registry(setter, (TransitionContext<Order, State, Id> context) -> {
            context.cascade(ITEM, first, Id.PREPARE);
            context.cascade(ITEM, second, Id.PREPARE);
            return ActionOutcome.ok();
        });
    }

    private static MachineRegistry registry(BiConsumer<? super Tracked, ? super State> setter,
            TransitionAction<Order, State, Id> prepare) {
        return MachineRegistry.builder()
                .add(StateMachines.from(orderGraph(prepare))
                        .state(Order::state, setter).identity(Order::id))
                .add(StateMachines.from(itemGraph()).state(Item::state, setter).identity(Item::id))
                .build();
    }

    private static GraphDefinition<Order, State, Id> orderGraph(TransitionAction<Order, State, Id> prepare) {
        return StateGraph.define(ORDER).initial(State.NEW)
                .terminal(State.DONE).terminal(State.FAILED)
                .statePolicy(State.NEW, "x").statePolicy(State.READY, "x")
                .transition(Id.PREPARE, State.NEW, State.READY).action("prepare", prepare).endTransition()
                .transition(Id.RECOVER, State.NEW, State.FAILED).endTransition()
                .transition(Id.FINISH, State.READY, State.DONE).endTransition()
                .build();
    }

    private static GraphDefinition<Item, State, Id> itemGraph() {
        return StateGraph.define(ITEM).initial(State.NEW)
                .terminal(State.DONE).terminal(State.FAILED)
                .statePolicy(State.NEW, "x").statePolicy(State.READY, "x")
                .transition(Id.PREPARE, State.NEW, State.READY).endTransition()
                .transition(Id.RECOVER, State.NEW, State.FAILED).endTransition()
                .transition(Id.FINISH, State.READY, State.DONE).endTransition()
                .build();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the latch was never released");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
