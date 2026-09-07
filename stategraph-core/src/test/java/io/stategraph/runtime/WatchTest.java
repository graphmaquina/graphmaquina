package io.stategraph.runtime;

import static io.stategraph.fixtures.DocStatus.ARCHIVED;
import static io.stategraph.fixtures.DocStatus.DRAFT;
import static io.stategraph.fixtures.DocStatus.PUBLISHED;
import static io.stategraph.fixtures.DocStatus.REVIEW;
import static io.stategraph.fixtures.Keys.DOCS;
import static io.stategraph.fixtures.Keys.ITEMS;
import static io.stategraph.fixtures.Keys.ORDERS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.stategraph.api.ActionOutcome;
import io.stategraph.api.GraphDefinition;
import io.stategraph.api.Outcome;
import io.stategraph.api.PeerView;
import io.stategraph.api.Reaction;
import io.stategraph.api.StateMachine;
import io.stategraph.api.TransitionContext;
import io.stategraph.dsl.StateGraph;
import io.stategraph.error.ReentrantTransitionException;
import io.stategraph.error.TransitionAbortedException;
import io.stategraph.error.UninitializedStateException;
import io.stategraph.error.WatchChainExceededException;
import io.stategraph.fixtures.Doc;
import io.stategraph.fixtures.DocStatus;
import io.stategraph.fixtures.GuardProbe;
import io.stategraph.fixtures.Item;
import io.stategraph.fixtures.ItemStatus;
import io.stategraph.fixtures.Order;
import io.stategraph.fixtures.OrderGraphs;
import io.stategraph.fixtures.OrderStatus;
import io.stategraph.fixtures.Probe;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class WatchTest {

    final Probe<Order, OrderStatus> shipOrder = new Probe<>();
    final Probe<Order, OrderStatus> cancelOrder = new Probe<>();
    final GuardProbe<Order, OrderStatus> retryBudget = new GuardProbe<>();
    final GuardProbe<Order, OrderStatus> allPacked = new GuardProbe<>();
    final Probe<Item, ItemStatus> packItem = new Probe<>();
    final MachineRegistry registry = MachineRegistry.builder()
            .add(StateMachines.from(OrderGraphs.order(
                            (TransitionContext<Order, OrderStatus> ctx) -> CompletableFuture.completedFuture(ActionOutcome.ok()),
                            shipOrder, cancelOrder, retryBudget, allPacked))
                    .state(Order::status, Order::setStatus).identity(Order::id).asyncTimeout(Duration.ofSeconds(5)))
            .add(StateMachines.from(OrderGraphs.item(packItem))
                    .state(Item::status, Item::setStatus).identity(Item::id))
            .build();
    final StateMachine<Order, OrderStatus> orders = registry.of(ORDERS);
    final StateMachine<Item, ItemStatus> items = registry.of(ITEMS);

    @Test
    void theLastPackedItemShipsTheOrder() {
        Order order = new Order("o1", OrderStatus.PAID);
        new Item("i1", ItemStatus.PACKED, Optional.of(order));
        Item i2 = new Item("i2", ItemStatus.PACKING, Optional.of(order));
        Outcome<Item, ItemStatus> outcome = items.transitionTo(i2, ItemStatus.PACKED);
        assertInstanceOf(Outcome.Applied.class, outcome);
        Outcome.Applied<Item, ItemStatus> applied = (Outcome.Applied<Item, ItemStatus>) outcome;
        assertEquals(1, applied.reactions().size());
        Reaction.Done done = assertInstanceOf(Reaction.Done.class, applied.reactions().get(0));
        assertEquals(ORDERS, done.key());
        assertInstanceOf(Outcome.Applied.class, done.outcome());
        Outcome.Applied<?, ?> shipped = (Outcome.Applied<?, ?>) done.outcome();
        assertEquals(OrderStatus.SHIPPED, shipped.to());
        assertEquals(OrderStatus.SHIPPED, order.status());
        assertEquals(List.of("Order#o1:PAID>SHIPPED@0"), shipOrder.calls);
    }

    @Test
    void notAllPackedYieldsAnIgnoredReaction() {
        Order order = new Order("o1", OrderStatus.PAID);
        new Item("i1", ItemStatus.PENDING, Optional.of(order));
        Item i2 = new Item("i2", ItemStatus.PACKING, Optional.of(order));
        Outcome<Item, ItemStatus> outcome = items.transitionTo(i2, ItemStatus.PACKED);
        assertInstanceOf(Outcome.Applied.class, outcome);
        Outcome.Applied<Item, ItemStatus> applied = (Outcome.Applied<Item, ItemStatus>) outcome;
        Reaction.Done done = assertInstanceOf(Reaction.Done.class, applied.reactions().get(0));
        assertInstanceOf(Outcome.Ignored.class, done.outcome());
        assertEquals(OrderStatus.PAID, order.status());
    }

    @Test
    void noOwnerOrOwnerOutsideTheWatchedStateMeansNoReaction() {
        Item orphan = new Item("i1", ItemStatus.PACKING, Optional.empty());
        Outcome<Item, ItemStatus> orphanOutcome = items.transitionTo(orphan, ItemStatus.PACKED);
        assertInstanceOf(Outcome.Applied.class, orphanOutcome);
        Outcome.Applied<Item, ItemStatus> orphanApplied = (Outcome.Applied<Item, ItemStatus>) orphanOutcome;
        assertEquals(List.of(), orphanApplied.reactions());
        Order fresh = new Order("o2", OrderStatus.NEW);
        Item owned = new Item("i2", ItemStatus.PACKING, Optional.of(fresh));
        Outcome<Item, ItemStatus> ownedOutcome = items.transitionTo(owned, ItemStatus.PACKED);
        assertInstanceOf(Outcome.Applied.class, ownedOutcome);
        Outcome.Applied<Item, ItemStatus> ownedApplied = (Outcome.Applied<Item, ItemStatus>) ownedOutcome;
        assertEquals(List.of(), ownedApplied.reactions());
    }

    @Test
    void oneReactionPerOwnerEvenIfSeveralPeersMove() {
        packItem.behaviour = (TransitionContext<Item, ItemStatus> ctx) -> {
            if (ctx.entity().id().equals("i1")) {
                ctx.cascade(ITEMS, ctx.entity().order().orElseThrow().items().get(1), ItemStatus.PACKED);
            }
            return ActionOutcome.ok();
        };
        Order order = new Order("o1", OrderStatus.PAID);
        Item i1 = new Item("i1", ItemStatus.PACKING, Optional.of(order));
        new Item("i2", ItemStatus.PACKING, Optional.of(order));
        Outcome<Item, ItemStatus> outcome = items.transitionTo(i1, ItemStatus.PACKED);
        assertInstanceOf(Outcome.Applied.class, outcome);
        Outcome.Applied<Item, ItemStatus> applied = (Outcome.Applied<Item, ItemStatus>) outcome;
        assertEquals(1, applied.reactions().size());
        assertEquals(OrderStatus.SHIPPED, order.status());
    }

    @Test
    void aFailedReactionIsReportedNotPropagated() {
        shipOrder.behaviour = (TransitionContext<Order, OrderStatus> ctx) -> {
            throw new IllegalStateException("carrier is down");
        };
        Order order = new Order("o1", OrderStatus.PAID);
        Item i1 = new Item("i1", ItemStatus.PACKING, Optional.of(order));
        Outcome<Item, ItemStatus> outcome = items.transitionTo(i1, ItemStatus.PACKED);
        assertInstanceOf(Outcome.Applied.class, outcome);
        Outcome.Applied<Item, ItemStatus> applied = (Outcome.Applied<Item, ItemStatus>) outcome;
        Reaction.Aborted aborted = assertInstanceOf(Reaction.Aborted.class, applied.reactions().get(0));
        assertInstanceOf(TransitionAbortedException.class, aborted.cause());
        assertEquals(ItemStatus.PACKED, i1.status());
        assertEquals(OrderStatus.PAID, order.status());
    }

    @Test
    void ownerThatCannotBeReadIsReportedAsAborted() {
        Order order = new Order("o1", null);
        Item i1 = new Item("i1", ItemStatus.PACKING, Optional.of(order));
        Outcome<Item, ItemStatus> outcome = items.transitionTo(i1, ItemStatus.PACKED);
        assertInstanceOf(Outcome.Applied.class, outcome);
        Outcome.Applied<Item, ItemStatus> applied = (Outcome.Applied<Item, ItemStatus>) outcome;
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
        Outcome<Order, OrderStatus> firstOutcome = orders.reevaluate(paid);
        assertInstanceOf(Outcome.Applied.class, firstOutcome);
        assertEquals(OrderStatus.SHIPPED, paid.status());
        Order fresh = new Order("o2", OrderStatus.NEW);
        Outcome<Order, OrderStatus> secondOutcome = orders.reevaluate(fresh);
        assertInstanceOf(Outcome.Ignored.class, secondOutcome);
        Outcome.Ignored<Order, OrderStatus> ignored = (Outcome.Ignored<Order, OrderStatus>) secondOutcome;
        assertEquals(OrderStatus.NEW, ignored.from());
    }

    @Test
    void reevaluateFromInsideAnActionIsReentrant() {
        packItem.behaviour = (TransitionContext<Item, ItemStatus> ctx) -> {
            orders.reevaluate(ctx.entity().order().orElseThrow());
            return ActionOutcome.ok();
        };
        Order order = new Order("o1", OrderStatus.PAID);
        Item item = new Item("i1", ItemStatus.PACKING, Optional.of(order));
        assertThrows(ReentrantTransitionException.class, () -> items.transitionTo(item, ItemStatus.PACKED));
        assertEquals(ItemStatus.PACKING, item.status());
        assertEquals(OrderStatus.PAID, order.status());
    }

    @Test
    void registryRejectsAWatchOnAnUnregisteredPeer() {
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> MachineRegistry.builder()
                .add(StateMachines.from(OrderGraphs.order(
                                (TransitionContext<Order, OrderStatus> ctx) -> CompletableFuture.completedFuture(ActionOutcome.ok()),
                                shipOrder, cancelOrder, retryBudget, allPacked))
                        .state(Order::status, Order::setStatus).identity(Order::id).asyncTimeout(Duration.ofSeconds(5)))
                .build());
        assertTrue(error.getMessage().contains("Item/ItemStatus"));
    }

    @Test
    void reactionChainIsBounded() {
        GraphDefinition<Doc, DocStatus> pingPong = StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED).allowsCycle(REVIEW)
                .from(DRAFT).to(REVIEW).move().stay()
                    .watches(DOCS, (Doc d) -> List.of(d), (Doc d) -> Optional.of(d))
                        .when("always", (Doc d, Collection<? extends Doc> peers, PeerView<Doc, DocStatus> view) -> true).goTo(REVIEW)
                    .forbidOthers("x")
                .from(REVIEW).to(DRAFT).move().to(PUBLISHED).move().stay()
                    .watches(DOCS, (Doc d) -> List.of(d), (Doc d) -> Optional.of(d))
                        .when("always", (Doc d, Collection<? extends Doc> peers, PeerView<Doc, DocStatus> view) -> true).goTo(DRAFT)
                    .forbidOthers("x")
                .from(PUBLISHED).to(ARCHIVED).move().stay().forbidOthers("x")
                .from(ARCHIVED).stay().forbidOthers("x")
                .build();
        MachineRegistry docs = MachineRegistry.builder()
                .add(StateMachines.from(pingPong).state(Doc::status, Doc::setStatus).identity(Doc::id))
                .maxWatchChain(2)
                .build();
        Doc doc = new Doc("d1", DRAFT);
        Outcome<Doc, DocStatus> rootOutcome = docs.of(DOCS).transitionTo(doc, REVIEW);
        assertInstanceOf(Outcome.Applied.class, rootOutcome);
        Outcome.Applied<Doc, DocStatus> root = (Outcome.Applied<Doc, DocStatus>) rootOutcome;
        Reaction.Done first = assertInstanceOf(Reaction.Done.class, root.reactions().get(0));
        assertInstanceOf(Outcome.Applied.class, first.outcome());
        Outcome.Applied<?, ?> firstOutcome = (Outcome.Applied<?, ?>) first.outcome();
        Reaction.Done second = assertInstanceOf(Reaction.Done.class, firstOutcome.reactions().get(0));
        assertInstanceOf(Outcome.Applied.class, second.outcome());
        Outcome.Applied<?, ?> secondOutcome = (Outcome.Applied<?, ?>) second.outcome();
        Reaction.Aborted third = assertInstanceOf(Reaction.Aborted.class, secondOutcome.reactions().get(0));
        assertInstanceOf(WatchChainExceededException.class, third.cause());
        assertEquals(REVIEW, doc.status());
    }
}
