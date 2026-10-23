package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static io.github.graphmaquina.fixtures.Keys.ITEMS;
import static io.github.graphmaquina.fixtures.Keys.ORDERS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.CascadePolicy;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.TransitionGuard;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.error.UnknownFacadeException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import io.github.graphmaquina.fixtures.Facades;
import io.github.graphmaquina.fixtures.Item;
import io.github.graphmaquina.fixtures.ItemStatus;
import io.github.graphmaquina.fixtures.ItemTransition;
import io.github.graphmaquina.fixtures.Order;
import io.github.graphmaquina.fixtures.OrderGraphs;
import io.github.graphmaquina.fixtures.OrderStatus;
import io.github.graphmaquina.fixtures.OrderTransition;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Machines addressed by facade type: {@code peer(Class)}, {@code cascade(Class, ...)} and
 * {@code cascadeAll(Class, ...)} resolve the machine registered with {@code as(...)}, an unregistered facade is an
 * unknown facade, a context checks its thread, its lifetime and the poison before it resolves a facade, and
 * {@code as(...)} twice is refused. Also {@code supplyAsync}, which runs off the calling thread and fails once the
 * registry is closed.
 */
class PeerByTypeTest {

    final AtomicReference<Thread> guardThread = new AtomicReference<>();

    private MachineRegistry registry(boolean registerItemsFacade) {
        StateMachines.Builder<Item, ItemStatus, ItemTransition> items = StateMachines.from(
                        OrderGraphs.item(
                                (TransitionContext<Item, ItemStatus, ItemTransition> ctx) -> ActionOutcome.ok()))
                .state(Item::status, Item::setStatus).identity(Item::id);
        if (registerItemsFacade) {
            items.as(Facades.Items.class);
        }
        return MachineRegistry.builder()
                .add(StateMachines.from(OrderGraphs.order(
                                (TransitionContext<Order, OrderStatus, OrderTransition> ctx) ->
                                        CompletableFuture.completedFuture(ActionOutcome.ok()),
                                (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> ActionOutcome.ok(),
                                (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> {
                                    List<Outcome<Item, ItemStatus, ItemTransition>> outcomes = ctx.cascadeAll(
                                            Facades.Items.class, ctx.entity().items(), ItemTransition.CANCEL,
                                            CascadePolicy.COLLECT);
                                    return outcomes.size() == ctx.entity().items().size()
                                            ? ActionOutcome.ok() : ActionOutcome.failed("missing");
                                },
                                (GuardContext<Order, OrderStatus, OrderTransition> ctx) -> GuardResult.pass(),
                                (GuardContext<Order, OrderStatus, OrderTransition> ctx) -> {
                                    guardThread.set(Thread.currentThread());
                                    return ctx.peer(Facades.Items.class).allIn(ctx.entity().items(), ItemStatus.PACKED)
                                            ? GuardResult.pass() : GuardResult.reject("unpacked");
                                }))
                        .state(Order::status, Order::setStatus).identity(Order::id).asyncTimeout(Duration.ofSeconds(5))
                        .as(Facades.Orders.class))
                .add(items)
                .build();
    }

    @Test
    void cascadeAllAndPeerResolveTheMachineByFacadeType() {
        try (MachineRegistry registry = registry(true)) {
            StateMachine<Order, OrderStatus, OrderTransition> orders = registry.of(ORDERS);
            Order order = new Order("o1", OrderStatus.NEW);
            new Item("i1", ItemStatus.PENDING, Optional.of(order));
            assertInstanceOf(Outcome.Applied.class, orders.execute(order, OrderTransition.CANCEL));
            assertEquals(ItemStatus.CANCELLED, order.items().get(0).status());

            Order paid = new Order("o2", OrderStatus.PAID);
            new Item("i2", ItemStatus.PENDING, Optional.of(paid));
            Outcome<Order, OrderStatus, OrderTransition> rejected = orders.execute(paid, OrderTransition.SHIP);
            assertInstanceOf(Outcome.Rejected.class, rejected);
        }
    }

    @Test
    void unregisteredFacadeTypeIsAnUnknownFacade() {
        try (MachineRegistry registry = registry(false)) {
            Order order = new Order("o1", OrderStatus.NEW);
            new Item("i1", ItemStatus.PENDING, Optional.of(order));
            TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                    () -> registry.of(ORDERS).execute(order, OrderTransition.CANCEL));
            assertInstanceOf(UnknownFacadeException.class, error.getCause());
        }
    }

    @Test
    void singleCascadeByFacadeType() {
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(OrderGraphs.order(
                                (TransitionContext<Order, OrderStatus, OrderTransition> ctx) ->
                                        CompletableFuture.completedFuture(ActionOutcome.ok()),
                                (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> ActionOutcome.ok(),
                                (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> {
                                    ctx.cascade(Facades.Items.class, ctx.entity().items().get(0),
                                            ItemTransition.CANCEL);
                                    return ActionOutcome.ok();
                                },
                                (GuardContext<Order, OrderStatus, OrderTransition> ctx) -> GuardResult.pass(),
                                (GuardContext<Order, OrderStatus, OrderTransition> ctx) -> GuardResult.pass()))
                        .state(Order::status, Order::setStatus).identity(Order::id).asyncTimeout(Duration.ofSeconds(5)))
                .add(StateMachines.from(OrderGraphs.item(
                                (TransitionContext<Item, ItemStatus, ItemTransition> ctx) -> ActionOutcome.ok()))
                        .state(Item::status, Item::setStatus).identity(Item::id).as(Facades.Items.class))
                .build()) {
            Order order = new Order("o1", OrderStatus.NEW);
            Item item = new Item("i1", ItemStatus.PENDING, Optional.of(order));
            assertInstanceOf(Outcome.Applied.class, registry.of(ORDERS).execute(order, OrderTransition.CANCEL));
            assertEquals(ItemStatus.CANCELLED, item.status());
        }
    }

    /**
     * A cascade addressed by facade type starts a step of its own, so the child runs with no input of the call
     * that cascaded to it, and the parent keeps reading the very value it was given. The child is driven from
     * PACKING to PACKED, the one edge of its graph that runs an action, so the assertion observes a real action.
     */
    @Test
    void cascadeByClassAndCascadeAllByClassDoNotInheritInput() {
        for (boolean all : List.of(false, true)) {
            Object value = new Object();
            AtomicInteger childCalls = new AtomicInteger();
            try (MachineRegistry registry = MachineRegistry.builder()
                    .add(StateMachines.from(OrderGraphs.order(
                                    (TransitionContext<Order, OrderStatus, OrderTransition> ctx) ->
                                            CompletableFuture.completedFuture(ActionOutcome.ok()),
                                    (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> ActionOutcome.ok(),
                                    (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> {
                                        assertSame(value, ctx.input().orElseThrow());
                                        if (all) {
                                            ctx.cascadeAll(Facades.Items.class, ctx.entity().items(),
                                                    ItemTransition.PACK, CascadePolicy.COLLECT);
                                        } else {
                                            ctx.cascade(Facades.Items.class, ctx.entity().items().getFirst(),
                                                    ItemTransition.PACK);
                                        }
                                        assertSame(value, ctx.input().orElseThrow());
                                        return ActionOutcome.ok();
                                    },
                                    (GuardContext<Order, OrderStatus, OrderTransition> ctx) -> GuardResult.pass(),
                                    (GuardContext<Order, OrderStatus, OrderTransition> ctx) -> GuardResult.pass()))
                            .state(Order::status, Order::setStatus).identity(Order::id)
                            .asyncTimeout(Duration.ofSeconds(5)))
                    .add(StateMachines.from(OrderGraphs.item(
                                    (TransitionContext<Item, ItemStatus, ItemTransition> ctx) -> {
                                        assertTrue(ctx.input().isEmpty());
                                        childCalls.incrementAndGet();
                                        return ActionOutcome.ok();
                                    })).state(Item::status, Item::setStatus).identity(Item::id).as(Facades.Items.class))
                    .build()) {
                Order order = new Order("o", OrderStatus.NEW);
                Item item = new Item("i", ItemStatus.PACKING, Optional.of(order));
                assertInstanceOf(Outcome.Applied.class,
                        registry.of(ORDERS).execute(order, OrderTransition.CANCEL, value));
                assertEquals(ItemStatus.PACKED, item.status());
                assertEquals(1, childCalls.get());
            }
        }
    }

    /**
     * A standalone doc machine whose SUBMIT edge runs the test's guard and then its action. Its registry holds no
     * item machine, so {@code Facades.Items} names none.
     */
    private static StateMachine<Doc, DocStatus, DocTransition> docs(
            TransitionGuard<Doc, DocStatus, DocTransition> guard,
            TransitionAction<Doc, DocStatus, DocTransition> action) {
        GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS)
                .initial(DocStatus.DRAFT).terminal(DocStatus.ARCHIVED)
                .statePolicy(DocStatus.DRAFT, "x").statePolicy(DocStatus.REVIEW, "x")
                .statePolicy(DocStatus.PUBLISHED, "x")
                .transition(DocTransition.SUBMIT, DocStatus.DRAFT, DocStatus.REVIEW)
                    .guard("Peeks", guard).action("Submit", action).endTransition()
                .transition(DocTransition.PUBLISH, DocStatus.REVIEW, DocStatus.PUBLISHED).endTransition()
                .transition(DocTransition.ARCHIVE, DocStatus.PUBLISHED, DocStatus.ARCHIVED).endTransition()
                .build();
        return StateMachines.from(graph).state(Doc::status, Doc::setStatus).build();
    }

    /** The simple name of what {@code call} throws, or "nothing" when it returns. */
    private static String thrownBy(Runnable call) {
        try {
            call.run();
            return "nothing";
        } catch (RuntimeException error) {
            return error.getClass().getSimpleName();
        }
    }

    /** What {@link #thrownBy} reports for {@code call} run on a thread of its own. */
    private static String thrownOnAnotherThread(Runnable call) {
        AtomicReference<String> thrown = new AtomicReference<>();
        Thread other = new Thread(() -> thrown.set(thrownBy(call)));
        other.start();
        try {
            other.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return thrown.get();
    }

    /**
     * Every call of a context that names a facade checks the context before it resolves the facade, a read as well
     * as a cascade: from another thread it reports the wrong thread, and once the transaction has ended a stale
     * context, even when the facade names no machine of the registry.
     */
    @Test
    void aContextChecksItsThreadAndItsLifetimeBeforeItResolvesAFacade() {
        Item item = new Item("i1", ItemStatus.PACKING, Optional.empty());
        AtomicReference<GuardContext<Doc, DocStatus, DocTransition>> guard = new AtomicReference<>();
        AtomicReference<TransitionContext<Doc, DocStatus, DocTransition>> action = new AtomicReference<>();
        List<String> offThread = new ArrayList<>();
        StateMachine<Doc, DocStatus, DocTransition> machine = docs(
                (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                    guard.set(ctx);
                    offThread.add("guard peer: " + thrownOnAnotherThread(() -> ctx.peer(Facades.Items.class)));
                    return GuardResult.pass();
                },
                (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                    action.set(ctx);
                    offThread.add("action peer: " + thrownOnAnotherThread(() -> ctx.peer(Facades.Items.class)));
                    offThread.add("action cascade: " + thrownOnAnotherThread(
                            () -> ctx.cascade(Facades.Items.class, item, ItemTransition.PACK)));
                    offThread.add("action cascadeAll: " + thrownOnAnotherThread(() -> ctx.cascadeAll(
                            Facades.Items.class, List.of(item), ItemTransition.PACK, CascadePolicy.FAIL_FAST)));
                    return ActionOutcome.ok();
                });
        Doc doc = new Doc("1", DocStatus.DRAFT);
        assertInstanceOf(Outcome.Applied.class, machine.execute(doc, DocTransition.SUBMIT));
        List<String> ends = new ArrayList<>(offThread);
        ends.add("stale guard peer: " + thrownBy(() -> guard.get().peer(Facades.Items.class)));
        ends.add("stale action peer: " + thrownBy(() -> action.get().peer(Facades.Items.class)));
        ends.add("stale action cascade: " + thrownBy(
                () -> action.get().cascade(Facades.Items.class, item, ItemTransition.PACK)));
        ends.add("stale action cascadeAll: " + thrownBy(() -> action.get().cascadeAll(
                Facades.Items.class, List.of(item), ItemTransition.PACK, CascadePolicy.FAIL_FAST)));
        assertEquals(List.of("guard peer: WrongThreadException", "action peer: WrongThreadException",
                "action cascade: WrongThreadException", "action cascadeAll: WrongThreadException",
                "stale guard peer: StaleContextException", "stale action peer: StaleContextException",
                "stale action cascade: StaleContextException", "stale action cascadeAll: StaleContextException"), ends);
        assertEquals(DocStatus.REVIEW, doc.status());
        assertEquals(ItemStatus.PACKING, item.status());
    }

    /**
     * In a poisoned transaction a peer read by facade reports the poison before it resolves the facade, as a read
     * by key and a cascade by facade do, even when the facade names no machine of the registry.
     */
    @Test
    void aPoisonedTransactionIsReportedBeforeAFacadeIsResolved() {
        Item item = new Item("i1", ItemStatus.PACKING, Optional.empty());
        List<String> ends = new ArrayList<>();
        StateMachine<Doc, DocStatus, DocTransition> machine = docs(
                (GuardContext<Doc, DocStatus, DocTransition> ctx) -> GuardResult.pass(),
                (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                    try {
                        ctx.cascade(DOCS, new Doc("2", DocStatus.DRAFT), DocTransition.PUBLISH);
                    } catch (TransitionAbortedException rejected) {
                        ends.add("first cascade: " + rejected.getClass().getSimpleName());
                    }
                    ends.add("peer by facade: " + thrownBy(() -> ctx.peer(Facades.Items.class)));
                    ends.add("peer by key: " + thrownBy(() -> ctx.peer(ITEMS)));
                    ends.add("cascade by facade: " + thrownBy(
                            () -> ctx.cascade(Facades.Items.class, item, ItemTransition.PACK)));
                    return ActionOutcome.ok();
                });
        Doc doc = new Doc("1", DocStatus.DRAFT);
        assertThrows(TransitionAbortedException.class, () -> machine.execute(doc, DocTransition.SUBMIT));
        assertEquals(List.of("first cascade: TransitionAbortedException",
                "peer by facade: PoisonedUnitOfWorkException", "peer by key: PoisonedUnitOfWorkException",
                "cascade by facade: PoisonedUnitOfWorkException"), ends);
        assertEquals(DocStatus.DRAFT, doc.status());
    }

    /** A null entity in a cascade by facade is reported under the name {@code TransitionContext} gives it. */
    @Test
    void aNullEntityInACascadeByFacadeIsReportedUnderTheInterfacesName() {
        List<String> messages = new ArrayList<>();
        StateMachine<Doc, DocStatus, DocTransition> machine = docs(
                (GuardContext<Doc, DocStatus, DocTransition> ctx) -> GuardResult.pass(),
                (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                    try {
                        ctx.cascade(Facades.Docs.class, null, DocTransition.PUBLISH);
                        messages.add("nothing thrown");
                    } catch (NullPointerException error) {
                        messages.add(error.getMessage());
                    }
                    return ActionOutcome.ok();
                });
        assertInstanceOf(Outcome.Applied.class, machine.execute(new Doc("1", DocStatus.DRAFT), DocTransition.SUBMIT));
        assertEquals(List.of("entity is marked non-null but is null"), messages);
    }

    @Test
    void supplyAsyncRunsOffTheCallingThreadAndFailsAfterClose() throws InterruptedException, ExecutionException {
        MachineRegistry registry = registry(true);
        StateMachine<Order, OrderStatus, OrderTransition> orders = registry.of(ORDERS);
        Order order = new Order("o1", OrderStatus.NEW);
        Thread caller = Thread.currentThread();
        Thread worker = orders.supplyAsync(Thread::currentThread).get();
        assertNotSame(caller, worker);
        assertInstanceOf(Outcome.Applied.class,
                orders.supplyAsync(() -> orders.execute(order, OrderTransition.CHARGE)).get());
        registry.close();
        CompletableFuture<Integer> afterClose = orders.supplyAsync(() -> 1);
        assertTrue(afterClose.isCompletedExceptionally());
    }

    @Test
    void asTwiceOnTheSameBuilderIsRejected() {
        StateMachines.Builder<Order, OrderStatus, OrderTransition> wrong = StateMachines.from(OrderGraphs.order(
                        (TransitionContext<Order, OrderStatus, OrderTransition> ctx) ->
                                CompletableFuture.completedFuture(ActionOutcome.ok()),
                        (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> ActionOutcome.ok(),
                        (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> ActionOutcome.ok(),
                        (GuardContext<Order, OrderStatus, OrderTransition> ctx) -> GuardResult.pass(),
                        (GuardContext<Order, OrderStatus, OrderTransition> ctx) -> GuardResult.pass()))
                .state(Order::status, Order::setStatus).identity(Order::id).asyncTimeout(Duration.ofSeconds(5));
        assertThrows(IllegalStateException.class, () -> wrong.as(Facades.Orders.class).as(Facades.Orders.class));
    }
}
