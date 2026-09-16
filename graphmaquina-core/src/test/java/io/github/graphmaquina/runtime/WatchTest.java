package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.ARCHIVE;
import static io.github.graphmaquina.fixtures.DocTransition.PUBLISH;
import static io.github.graphmaquina.fixtures.DocTransition.SEND_BACK;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static io.github.graphmaquina.fixtures.Keys.ITEMS;
import static io.github.graphmaquina.fixtures.Keys.ORDERS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.IgnoreReason;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.PeerView;
import io.github.graphmaquina.api.Reaction;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.error.ReentrantTransitionException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.error.UninitializedStateException;
import io.github.graphmaquina.error.WatchChainExceededException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import io.github.graphmaquina.fixtures.GuardProbe;
import io.github.graphmaquina.fixtures.Item;
import io.github.graphmaquina.fixtures.ItemStatus;
import io.github.graphmaquina.fixtures.ItemTransition;
import io.github.graphmaquina.fixtures.Order;
import io.github.graphmaquina.fixtures.OrderGraphs;
import io.github.graphmaquina.fixtures.OrderStatus;
import io.github.graphmaquina.fixtures.OrderTransition;
import io.github.graphmaquina.fixtures.Probe;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class WatchTest {

    final Probe<Order, OrderStatus, OrderTransition> shipOrder = new Probe<>();
    final Probe<Order, OrderStatus, OrderTransition> cancelOrder = new Probe<>();
    final GuardProbe<Order, OrderStatus, OrderTransition> retryBudget = new GuardProbe<>();
    final GuardProbe<Order, OrderStatus, OrderTransition> allPacked = new GuardProbe<>();
    final Probe<Item, ItemStatus, ItemTransition> packItem = new Probe<>();
    final MachineRegistry registry = MachineRegistry.builder()
            .add(StateMachines.from(OrderGraphs.order(
                            (TransitionContext<Order, OrderStatus, OrderTransition> ctx) ->
                                    CompletableFuture.completedFuture(ActionOutcome.ok()),
                            shipOrder, cancelOrder, retryBudget, allPacked))
                    .state(Order::status, Order::setStatus).identity(Order::id).asyncTimeout(Duration.ofSeconds(5)))
            .add(StateMachines.from(OrderGraphs.item(packItem))
                    .state(Item::status, Item::setStatus).identity(Item::id))
            .build();
    final StateMachine<Order, OrderStatus, OrderTransition> orders = registry.of(ORDERS);
    final StateMachine<Item, ItemStatus, ItemTransition> items = registry.of(ITEMS);

    @Test
    void theLastPackedItemShipsTheOrder() {
        Order order = new Order("o1", OrderStatus.PAID);
        new Item("i1", ItemStatus.PACKED, Optional.of(order));
        Item i2 = new Item("i2", ItemStatus.PACKING, Optional.of(order));
        Outcome<Item, ItemStatus, ItemTransition> outcome = items.execute(i2, ItemTransition.PACK);
        assertInstanceOf(Outcome.Applied.class, outcome);
        Outcome.Applied<Item, ItemStatus, ItemTransition> applied =
                (Outcome.Applied<Item, ItemStatus, ItemTransition>) outcome;
        assertEquals(1, applied.reactions().size());
        Reaction.Done done = assertInstanceOf(Reaction.Done.class, applied.reactions().get(0));
        assertEquals(ORDERS, done.key());
        assertInstanceOf(Outcome.Applied.class, done.outcome());
        Outcome.Applied<?, ?, ?> shipped = (Outcome.Applied<?, ?, ?>) done.outcome();
        assertEquals(OrderStatus.SHIPPED, shipped.to());
        assertEquals(OrderTransition.SHIP, shipped.transition());
        assertEquals(OrderStatus.SHIPPED, order.status());
        assertEquals(List.of("Order#o1:PAID-SHIP>SHIPPED@0"), shipOrder.calls);
    }

    @Test
    void notAllPackedYieldsAnIgnoredReaction() {
        Order order = new Order("o1", OrderStatus.PAID);
        new Item("i1", ItemStatus.PENDING, Optional.of(order));
        Item i2 = new Item("i2", ItemStatus.PACKING, Optional.of(order));
        Outcome<Item, ItemStatus, ItemTransition> outcome = items.execute(i2, ItemTransition.PACK);
        assertInstanceOf(Outcome.Applied.class, outcome);
        Outcome.Applied<Item, ItemStatus, ItemTransition> applied =
                (Outcome.Applied<Item, ItemStatus, ItemTransition>) outcome;
        Reaction.Done done = assertInstanceOf(Reaction.Done.class, applied.reactions().get(0));
        assertInstanceOf(Outcome.Ignored.class, done.outcome());
        assertEquals(IgnoreReason.NO_RULE_MATCHED, ((Outcome.Ignored<?, ?, ?>) done.outcome()).reason());
        assertEquals(OrderStatus.PAID, order.status());
    }

    @Test
    void noOwnerOrOwnerOutsideTheWatchedStateMeansNoReaction() {
        Item orphan = new Item("i1", ItemStatus.PACKING, Optional.empty());
        Outcome<Item, ItemStatus, ItemTransition> orphanOutcome = items.execute(orphan, ItemTransition.PACK);
        assertInstanceOf(Outcome.Applied.class, orphanOutcome);
        Outcome.Applied<Item, ItemStatus, ItemTransition> orphanApplied =
                (Outcome.Applied<Item, ItemStatus, ItemTransition>) orphanOutcome;
        assertEquals(List.of(), orphanApplied.reactions());
        Order fresh = new Order("o2", OrderStatus.NEW);
        Item owned = new Item("i2", ItemStatus.PACKING, Optional.of(fresh));
        Outcome<Item, ItemStatus, ItemTransition> ownedOutcome = items.execute(owned, ItemTransition.PACK);
        assertInstanceOf(Outcome.Applied.class, ownedOutcome);
        Outcome.Applied<Item, ItemStatus, ItemTransition> ownedApplied =
                (Outcome.Applied<Item, ItemStatus, ItemTransition>) ownedOutcome;
        assertEquals(List.of(), ownedApplied.reactions());
    }

    @Test
    void oneReactionPerOwnerEvenIfSeveralPeersMove() {
        packItem.behaviour = (TransitionContext<Item, ItemStatus, ItemTransition> ctx) -> {
            if (ctx.entity().id().equals("i1")) {
                ctx.cascade(ITEMS, ctx.entity().order().orElseThrow().items().get(1), ItemTransition.PACK);
            }
            return ActionOutcome.ok();
        };
        Order order = new Order("o1", OrderStatus.PAID);
        Item i1 = new Item("i1", ItemStatus.PACKING, Optional.of(order));
        new Item("i2", ItemStatus.PACKING, Optional.of(order));
        Outcome<Item, ItemStatus, ItemTransition> outcome = items.execute(i1, ItemTransition.PACK);
        assertInstanceOf(Outcome.Applied.class, outcome);
        Outcome.Applied<Item, ItemStatus, ItemTransition> applied =
                (Outcome.Applied<Item, ItemStatus, ItemTransition>) outcome;
        assertEquals(1, applied.reactions().size());
        assertEquals(OrderStatus.SHIPPED, order.status());
    }

    @Test
    void aFailedReactionIsReportedNotPropagated() {
        shipOrder.behaviour = (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> {
            throw new IllegalStateException("carrier is down");
        };
        Order order = new Order("o1", OrderStatus.PAID);
        Item i1 = new Item("i1", ItemStatus.PACKING, Optional.of(order));
        Outcome<Item, ItemStatus, ItemTransition> outcome = items.execute(i1, ItemTransition.PACK);
        assertInstanceOf(Outcome.Applied.class, outcome);
        Outcome.Applied<Item, ItemStatus, ItemTransition> applied =
                (Outcome.Applied<Item, ItemStatus, ItemTransition>) outcome;
        Reaction.Aborted aborted = assertInstanceOf(Reaction.Aborted.class, applied.reactions().get(0));
        assertInstanceOf(TransitionAbortedException.class, aborted.cause());
        assertEquals(ItemStatus.PACKED, i1.status());
        assertEquals(OrderStatus.PAID, order.status());
    }

    @Test
    void ownerThatCannotBeReadIsReportedAsAborted() {
        Order order = new Order("o1", null);
        Item i1 = new Item("i1", ItemStatus.PACKING, Optional.of(order));
        Outcome<Item, ItemStatus, ItemTransition> outcome = items.execute(i1, ItemTransition.PACK);
        assertInstanceOf(Outcome.Applied.class, outcome);
        Outcome.Applied<Item, ItemStatus, ItemTransition> applied =
                (Outcome.Applied<Item, ItemStatus, ItemTransition>) outcome;
        assertEquals(1, applied.reactions().size());
        Reaction.Aborted aborted = assertInstanceOf(Reaction.Aborted.class, applied.reactions().get(0));
        assertEquals(ITEMS, aborted.key());
        assertEquals(i1, aborted.entity());
        assertInstanceOf(UninitializedStateException.class, aborted.cause());
    }

    @Test
    void manualReevaluate() {
        Order paid = new Order("o1", OrderStatus.PAID);
        new Item("i1", ItemStatus.PACKED, Optional.of(paid));
        Outcome<Order, OrderStatus, OrderTransition> firstOutcome = orders.reevaluate(paid);
        assertInstanceOf(Outcome.Applied.class, firstOutcome);
        assertEquals(OrderStatus.SHIPPED, paid.status());
        Order fresh = new Order("o2", OrderStatus.NEW);
        Outcome<Order, OrderStatus, OrderTransition> secondOutcome = orders.reevaluate(fresh);
        assertInstanceOf(Outcome.Ignored.class, secondOutcome);
        Outcome.Ignored<Order, OrderStatus, OrderTransition> ignored =
                (Outcome.Ignored<Order, OrderStatus, OrderTransition>) secondOutcome;
        assertEquals(OrderStatus.NEW, ignored.from());
        assertEquals(IgnoreReason.NO_WATCH, ignored.reason());
    }

    @Test
    void reevaluateFromInsideAnActionIsReentrant() {
        packItem.behaviour = (TransitionContext<Item, ItemStatus, ItemTransition> ctx) -> {
            orders.reevaluate(ctx.entity().order().orElseThrow());
            return ActionOutcome.ok();
        };
        Order order = new Order("o1", OrderStatus.PAID);
        Item item = new Item("i1", ItemStatus.PACKING, Optional.of(order));
        assertThrows(ReentrantTransitionException.class, () -> items.execute(item, ItemTransition.PACK));
        assertEquals(ItemStatus.PACKING, item.status());
        assertEquals(OrderStatus.PAID, order.status());
    }

    @Test
    void registryRejectsAWatchOnAnUnregisteredPeer() {
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> MachineRegistry.builder()
                .add(StateMachines.from(OrderGraphs.order(
                                (TransitionContext<Order, OrderStatus, OrderTransition> ctx) ->
                                        CompletableFuture.completedFuture(ActionOutcome.ok()),
                                shipOrder, cancelOrder, retryBudget, allPacked))
                        .state(Order::status, Order::setStatus).identity(Order::id).asyncTimeout(Duration.ofSeconds(5)))
                .build());
        assertTrue(error.getMessage().contains("Item/ItemStatus"));
    }

    @Test
    void reactionChainIsBounded() {
        GraphDefinition<Doc, DocStatus, DocTransition> pingPong = StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED).allowsCycle(REVIEW)
                .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(SEND_BACK, REVIEW, DRAFT).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .watch(DRAFT, DOCS, (Doc d) -> List.of(d), (Doc d) -> Optional.of(d))
                    .when("always", (Doc d, Collection<? extends Doc> peers, PeerView<Doc, DocStatus, DocTransition> view) -> true)
                        .execute(SUBMIT)
                    .endWatch()
                .watch(REVIEW, DOCS, (Doc d) -> List.of(d), (Doc d) -> Optional.of(d))
                    .when("always", (Doc d, Collection<? extends Doc> peers, PeerView<Doc, DocStatus, DocTransition> view) -> true)
                        .execute(SEND_BACK)
                    .endWatch()
                .build();
        MachineRegistry docs = MachineRegistry.builder()
                .add(StateMachines.from(pingPong).state(Doc::status, Doc::setStatus).identity(Doc::id))
                .maxWatchChain(2)
                .build();
        Doc doc = new Doc("d1", DRAFT);
        Outcome<Doc, DocStatus, DocTransition> rootOutcome = docs.of(DOCS).execute(doc, SUBMIT);
        assertInstanceOf(Outcome.Applied.class, rootOutcome);
        Outcome.Applied<Doc, DocStatus, DocTransition> root = (Outcome.Applied<Doc, DocStatus, DocTransition>) rootOutcome;
        Reaction.Done first = assertInstanceOf(Reaction.Done.class, root.reactions().get(0));
        assertInstanceOf(Outcome.Applied.class, first.outcome());
        Outcome.Applied<?, ?, ?> firstOutcome = (Outcome.Applied<?, ?, ?>) first.outcome();
        Reaction.Done second = assertInstanceOf(Reaction.Done.class, firstOutcome.reactions().get(0));
        assertInstanceOf(Outcome.Applied.class, second.outcome());
        Outcome.Applied<?, ?, ?> secondOutcome = (Outcome.Applied<?, ?, ?>) second.outcome();
        Reaction.Aborted third = assertInstanceOf(Reaction.Aborted.class, secondOutcome.reactions().get(0));
        assertInstanceOf(WatchChainExceededException.class, third.cause());
        assertEquals(REVIEW, doc.status());
    }

    @Test
    void watchRootsHaveNoInput() {
        Object input = new Object();
        shipOrder.behaviour = (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> {
            assertTrue(ctx.input().isEmpty());
            return ActionOutcome.ok();
        };
        Order automatic = new Order("automatic", OrderStatus.PAID);
        Item child = new Item("child", ItemStatus.PACKING, Optional.of(automatic));
        packItem.behaviour = (TransitionContext<Item, ItemStatus, ItemTransition> ctx) -> {
            assertSame(input, ctx.input().orElseThrow());
            return ActionOutcome.ok();
        };
        items.execute(child, ItemTransition.PACK, input);
        assertEquals(OrderStatus.SHIPPED, automatic.status());
        Order manual = new Order("manual", OrderStatus.PAID);
        new Item("packed", ItemStatus.PACKED, Optional.of(manual));
        assertInstanceOf(Outcome.Applied.class, orders.reevaluate(manual));
        assertEquals(2, shipOrder.calls.size());
    }
}
