package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static io.github.graphmaquina.fixtures.Keys.ITEMS;
import static io.github.graphmaquina.fixtures.Keys.ORDERS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.MachineObserver;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * The builders are single-use: once built, every method refuses, instead of accepting configuration that nothing
 * reads any more. A build that fails consumes nothing, so the problem it reports can be fixed and the build retried.
 */
class BuilderReuseTest {

    private static GraphDefinition<Doc, DocStatus, DocTransition> docs() {
        return StateGraph.define(DOCS)
                .initial(DocStatus.DRAFT).terminal(DocStatus.ARCHIVED)
                .statePolicy(DocStatus.DRAFT, "x").statePolicy(DocStatus.REVIEW, "x")
                .statePolicy(DocStatus.PUBLISHED, "x")
                .transition(DocTransition.SUBMIT, DocStatus.DRAFT, DocStatus.REVIEW).endTransition()
                .transition(DocTransition.PUBLISH, DocStatus.REVIEW, DocStatus.PUBLISHED).endTransition()
                .transition(DocTransition.ARCHIVE, DocStatus.PUBLISHED, DocStatus.ARCHIVED).endTransition()
                .build();
    }

    /** The order machine watches its items from PAID, so it needs the item machine beside it. */
    private static StateMachines.Builder<Order, OrderStatus, OrderTransition> orders() {
        return StateMachines.from(OrderGraphs.order(
                        (TransitionContext<Order, OrderStatus, OrderTransition> ctx) ->
                                CompletableFuture.completedFuture(ActionOutcome.ok()),
                        (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> ActionOutcome.ok(),
                        (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> ActionOutcome.ok(),
                        (GuardContext<Order, OrderStatus, OrderTransition> ctx) -> GuardResult.pass(),
                        (GuardContext<Order, OrderStatus, OrderTransition> ctx) -> GuardResult.pass()))
                .state(Order::status, Order::setStatus).identity(Order::id).asyncTimeout(Duration.ofSeconds(5));
    }

    private static StateMachines.Builder<Item, ItemStatus, ItemTransition> items() {
        return StateMachines.from(OrderGraphs.item(
                (TransitionContext<Item, ItemStatus, ItemTransition> ctx) -> ActionOutcome.ok()))
                .state(Item::status, Item::setStatus).identity(Item::id);
    }

    /** Runs every call and names those that were accepted: each one should have thrown {@code message}. */
    private static List<String> accepted(Map<String, Runnable> calls, String message) {
        List<String> accepted = new ArrayList<>();
        for (Map.Entry<String, Runnable> call : calls.entrySet()) {
            try {
                call.getValue().run();
                accepted.add(call.getKey());
            } catch (IllegalStateException e) {
                assertEquals(message, e.getMessage(), call.getKey());
            }
        }
        return accepted;
    }

    @Test
    void aBuiltMachineBuilderRefusesEveryMethod() {
        StateMachines.Builder<Doc, DocStatus, DocTransition> builder =
                StateMachines.from(docs()).state(Doc::status, Doc::setStatus);
        builder.build();
        Map<String, Runnable> calls = new LinkedHashMap<>();
        calls.put("state", () -> builder.state(Doc::status, Doc::setStatus));
        calls.put("identity", () -> builder.identity(Doc::id));
        calls.put("asyncTimeout", () -> builder.asyncTimeout(Duration.ofSeconds(1)));
        calls.put("asyncExecutor", () -> builder.asyncExecutor(Runnable::run));
        calls.put("as", () -> builder.as(Facades.Docs.class));
        calls.put("build", builder::build);
        assertEquals(List.of(), accepted(calls, "the builder for Doc/DocStatus was already used"));
    }

    @Test
    void aBuiltRegistryBuilderRefusesEveryMethod() {
        MachineRegistry.Builder builder = MachineRegistry.builder()
                .add(StateMachines.from(docs()).state(Doc::status, Doc::setStatus).identity(Doc::id));
        builder.build().close();
        Map<String, Runnable> calls = new LinkedHashMap<>();
        calls.put("add", () -> builder.add(items()));
        calls.put("maxCascadeDepth", () -> builder.maxCascadeDepth(3));
        calls.put("maxWatchChain", () -> builder.maxWatchChain(3));
        calls.put("listener", () -> builder.listener((MachineKey<?, ?, ?> key, Outcome<?, ?, ?> outcome) -> { }));
        calls.put("observer", () -> builder.observer(new MachineObserver() { }));
        calls.put("build", builder::build);
        assertEquals(List.of(), accepted(calls, "this MachineRegistry.Builder was already used"));
    }

    /** The first build fails on the second machine; the first is not consumed, and the fixed build succeeds. */
    @Test
    void aRegistryBuildThatFailsConsumesNothing() {
        StateMachines.Builder<Doc, DocStatus, DocTransition> docs =
                StateMachines.from(docs()).state(Doc::status, Doc::setStatus).identity(Doc::id);
        StateMachines.Builder<Item, ItemStatus, ItemTransition> items = StateMachines.from(OrderGraphs.item(
                        (TransitionContext<Item, ItemStatus, ItemTransition> ctx) -> ActionOutcome.ok()))
                .state(Item::status, Item::setStatus);
        MachineRegistry.Builder builder = MachineRegistry.builder().add(docs).add(items);
        IllegalStateException missing = assertThrows(IllegalStateException.class, builder::build);
        assertEquals("the machine Item/ItemStatus must declare identity(...) within a registry", missing.getMessage());
        items.identity(Item::id);
        try (MachineRegistry registry = builder.build()) {
            assertEquals(Set.of(DOCS, ITEMS), registry.keys());
        }
    }

    /**
     * The first machine builder is valid and the second fails its own checks: the build fails on the second without
     * consuming the first, which still takes configuration, and fixing the second is enough for the retry.
     */
    @Test
    void aRegistryBuildThatFailsOnALaterMachineConsumesNoEarlierOne() {
        StateMachines.Builder<Doc, DocStatus, DocTransition> docs =
                StateMachines.from(docs()).state(Doc::status, Doc::setStatus).identity(Doc::id);
        StateMachines.Builder<Item, ItemStatus, ItemTransition> items = StateMachines.from(OrderGraphs.item(
                        (TransitionContext<Item, ItemStatus, ItemTransition> ctx) -> ActionOutcome.ok()))
                .identity(Item::id);
        MachineRegistry.Builder builder = MachineRegistry.builder().add(docs).add(items);
        IllegalStateException missing = assertThrows(IllegalStateException.class, builder::build);
        assertEquals("the machine Item/ItemStatus does not declare state(getter, setter)", missing.getMessage());
        assertDoesNotThrow(() -> docs.asyncTimeout(Duration.ofSeconds(5)));
        items.state(Item::status, Item::setStatus);
        try (MachineRegistry registry = builder.build()) {
            assertEquals(Set.of(DOCS, ITEMS), registry.keys());
        }
    }

    /** The same when the later machine fails on its watch: the earlier one is not consumed by the failed build. */
    @Test
    void aRegistryBuildThatFailsOnALaterMachinesWatchConsumesNoEarlierOne() {
        StateMachines.Builder<Doc, DocStatus, DocTransition> docs =
                StateMachines.from(docs()).state(Doc::status, Doc::setStatus).identity(Doc::id);
        MachineRegistry.Builder builder = MachineRegistry.builder().add(docs).add(orders());
        IllegalStateException missing = assertThrows(IllegalStateException.class, builder::build);
        assertEquals("the watch of Order/OrderStatus.PAID points to Item/ItemStatus, which is not in the registry",
                missing.getMessage());
        assertDoesNotThrow(() -> docs.asyncTimeout(Duration.ofSeconds(5)));
        builder.add(items());
        try (MachineRegistry registry = builder.build()) {
            assertEquals(Set.of(DOCS, ORDERS, ITEMS), registry.keys());
        }
    }

    /** A watch on a machine missing from the registry fails the build, and adding that machine fixes it. */
    @Test
    void aRegistryBuildThatMissesAWatchedMachineConsumesNothing() {
        MachineRegistry.Builder builder = MachineRegistry.builder().add(orders());
        IllegalStateException missing = assertThrows(IllegalStateException.class, builder::build);
        assertTrue(missing.getMessage().contains("points to Item/ItemStatus"), missing.getMessage());
        builder.add(items());
        try (MachineRegistry registry = builder.build()) {
            assertEquals(Set.of(ORDERS, ITEMS), registry.keys());
        }
    }

    /** The same for a standalone machine: a build missing its accessors, then its asyncTimeout, is retried until it works. */
    @Test
    void aMachineBuildThatFailsConsumesNothing() {
        StateMachines.Builder<Doc, DocStatus, DocTransition> docs = StateMachines.from(docs());
        IllegalStateException noAccessors = assertThrows(IllegalStateException.class, docs::build);
        assertTrue(noAccessors.getMessage().contains("state(getter, setter)"), noAccessors.getMessage());
        docs.state(Doc::status, Doc::setStatus);
        StateMachine<Doc, DocStatus, DocTransition> machine = docs.build();
        assertInstanceOf(Outcome.Applied.class, machine.execute(new Doc("1", DocStatus.DRAFT), DocTransition.SUBMIT));

        StateMachines.Builder<Order, OrderStatus, OrderTransition> orders = StateMachines.from(OrderGraphs.orderWithoutWatch(
                        (TransitionContext<Order, OrderStatus, OrderTransition> ctx) ->
                                CompletableFuture.completedFuture(ActionOutcome.ok()),
                        (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> ActionOutcome.ok(),
                        (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> ActionOutcome.ok(),
                        (GuardContext<Order, OrderStatus, OrderTransition> ctx) -> GuardResult.pass(),
                        (GuardContext<Order, OrderStatus, OrderTransition> ctx) -> GuardResult.pass()))
                .state(Order::status, Order::setStatus);
        IllegalStateException noTimeout = assertThrows(IllegalStateException.class, orders::build);
        assertTrue(noTimeout.getMessage().contains("asyncTimeout"), noTimeout.getMessage());
        orders.asyncTimeout(Duration.ofSeconds(5));
        assertEquals(ORDERS, orders.build().key());
    }
}
