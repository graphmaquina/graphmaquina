package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.ARCHIVE;
import static io.github.graphmaquina.fixtures.DocTransition.PUBLISH;
import static io.github.graphmaquina.fixtures.DocTransition.RETOUCH;
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
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.PeerView;
import io.github.graphmaquina.api.Reaction;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.TransitionRecord;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.error.AfterCommitException;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * Watches over the order and item fixtures, and over a doc machine that watches itself: which commits notify a watch,
 * the reactions an outcome carries, a failed or unreadable reaction, manual reevaluation, the bounded reaction chain,
 * the reads a rule makes, and the input a reaction runs with.
 */
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
    final List<String> hooks = new ArrayList<>();
    final List<String> writes = new ArrayList<>();
    String failingHook = "";

    /** Records the hook that ran and throws from the one the test named, to stop the root before its watches. */
    private void hook(String name, Doc doc) {
        String call = name + ":" + doc.id();
        hooks.add(call);
        if (failingHook.equals(call)) {
            throw new IllegalStateException("the ledger is down");
        }
    }

    /**
     * A registry of one machine watching itself: a doc that moves resolves the observer as its owner only when
     * its identity is the watched one, and the observer reacts from PUBLISHED. An absent notification is then
     * observable, because a notification would archive the observer.
     */
    private MachineRegistry docsWatching(Doc observer, String watchedId, Probe<Doc, DocStatus, DocTransition> onSubmit) {
        GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED).allowsCycle(REVIEW)
                .statePolicy(DRAFT, "a draft is submitted")
                .statePolicy(REVIEW, "in review it is published, retouched or sent back")
                .statePolicy(PUBLISHED, "published only archives")
                .transition(SUBMIT, DRAFT, REVIEW).action("submit", onSubmit)
                    .afterCommit("onReview", (Doc doc) -> hook("review", doc)).endTransition()
                .transition(SEND_BACK, REVIEW, DRAFT)
                    .afterCommit("onDraft", (Doc doc) -> hook("draft", doc)).endTransition()
                .transition(RETOUCH, REVIEW, REVIEW)
                    .afterCommit("onRetouch", (Doc doc) -> hook("retouch", doc)).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .watch(PUBLISHED, DOCS, (Doc doc) -> List.of(doc),
                        (Doc moved) -> moved.id().equals(watchedId) ? Optional.of(observer) : Optional.<Doc>empty())
                    .when("always", (Doc doc, Collection<? extends Doc> peers,
                            PeerView<Doc, DocStatus, DocTransition> view) -> true)
                        .execute(ARCHIVE)
                    .endWatch()
                .build();
        return MachineRegistry.builder()
                .add(StateMachines.from(graph).state(Doc::status, (Doc doc, DocStatus status) -> {
                    writes.add(doc.id() + ":" + status);
                    doc.setStatus(status);
                }).identity(Doc::id))
                .build();
    }

    private static List<MachineKey<?, ?, ?>> keysOf(List<TransitionRecord<?, ?, ?>> records) {
        List<MachineKey<?, ?, ?>> keys = new ArrayList<>();
        for (TransitionRecord<?, ?, ?> record : records) {
            keys.add(record.key());
        }
        return keys;
    }

    @Test
    void watchReadsCallIdentityBeforeEveryCommittedStateRead() {
        List<String> calls = new ArrayList<>();
        Doc peer = new Doc("peer", REVIEW);
        boolean[] nullIdentity = {false};
        GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "submit").statePolicy(REVIEW, "publish").statePolicy(PUBLISHED, "archive")
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .watch(PUBLISHED, DOCS, (Doc doc) -> { calls.add("peers"); return List.of(peer); },
                        (Doc doc) -> Optional.<Doc>empty())
                    .when("first", (Doc doc, Collection<? extends Doc> peers,
                            PeerView<Doc, DocStatus, DocTransition> view) -> {
                        calls.add("first");
                        assertEquals(DOCS, view.key());
                        assertEquals(REVIEW, view.stateOf(peer));
                        peer.setStatus(PUBLISHED);
                        assertEquals(PUBLISHED, view.stateOf(peer));
                        return false;
                    }).execute(ARCHIVE)
                    .when("second", (Doc doc, Collection<? extends Doc> peers,
                            PeerView<Doc, DocStatus, DocTransition> view) -> {
                        calls.add("second");
                        assertEquals(PUBLISHED, view.stateOf(peer));
                        return false;
                    }).execute(ARCHIVE)
                .endWatch().build();
        try (MachineRegistry docs = MachineRegistry.builder().add(StateMachines.from(graph)
                .state((Doc doc) -> { calls.add("state:" + doc.id()); return doc.status(); }, Doc::setStatus)
                .identity((Doc doc) -> { calls.add("identity:" + doc.id()); return nullIdentity[0] ? null : doc.id(); })).build()) {
            Outcome.Ignored<?, ?, ?> outcome = assertInstanceOf(Outcome.Ignored.class,
                    docs.of(DOCS).reevaluate(new Doc("owner", PUBLISHED)));
            assertEquals(IgnoreReason.NO_RULE_MATCHED, outcome.reason());
            assertEquals(List.of("state:owner", "peers", "first", "identity:peer", "state:peer",
                    "identity:peer", "state:peer", "second", "identity:peer", "state:peer"), calls);
            calls.clear();
            nullIdentity[0] = true;
            NullPointerException identityError = assertThrows(NullPointerException.class,
                    () -> docs.of(DOCS).reevaluate(new Doc("owner", PUBLISHED)));
            assertEquals("identity of " + DOCS + " returned null", identityError.getMessage());
            assertEquals(List.of("state:owner", "peers", "first", "identity:peer"), calls);
            calls.clear();
            nullIdentity[0] = false;
            peer.setStatus(null);
            assertThrows(UninitializedStateException.class,
                    () -> docs.of(DOCS).reevaluate(new Doc("owner", PUBLISHED)));
            assertEquals(List.of("state:owner", "peers", "first", "identity:peer", "state:peer"), calls);
        }
    }

    @Test
    void theCommittedViewRejectsANullPeerBeforeAnyIdentityCall() {
        List<String> calls = new ArrayList<>();
        Doc peer = new Doc("peer", REVIEW);
        GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "submit").statePolicy(REVIEW, "publish").statePolicy(PUBLISHED, "archive")
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .watch(PUBLISHED, DOCS, (Doc doc) -> List.of(peer), (Doc doc) -> Optional.<Doc>empty())
                    .when("null peer", (Doc doc, Collection<? extends Doc> peers,
                            PeerView<Doc, DocStatus, DocTransition> view) -> {
                        NullPointerException error = assertThrows(NullPointerException.class,
                                () -> view.stateOf(null));
                        assertEquals("peer is marked non-null but is null", error.getMessage());
                        return false;
                    }).execute(ARCHIVE)
                .endWatch().build();
        try (MachineRegistry docs = MachineRegistry.builder().add(StateMachines.from(graph)
                .state(Doc::status, Doc::setStatus)
                .identity((Doc doc) -> { calls.add("identity:" + doc.id()); return doc.id(); })).build()) {
            Outcome.Ignored<?, ?, ?> outcome = assertInstanceOf(Outcome.Ignored.class,
                    docs.of(DOCS).reevaluate(new Doc("owner", PUBLISHED)));
            assertEquals(IgnoreReason.NO_RULE_MATCHED, outcome.reason());
            assertEquals(List.of(), calls);
        }
    }

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

    /**
     * A round trip that ends where it started moves nothing: its hops are recorded, their hooks run and the
     * setter still writes, but no watcher is notified. The move that follows is a real one and does notify,
     * which is what proves the fixture was wired to react.
     */
    @Test
    void aNetZeroRoundTripKeepsItsHopsAndNotifiesNoWatch() {
        Doc observer = new Doc("obs", PUBLISHED);
        Probe<Doc, DocStatus, DocTransition> onSubmit = new Probe<>();
        try (MachineRegistry docs = docsWatching(observer, "peer", onSubmit)) {
            Doc peer = new Doc("peer", DRAFT);
            Doc root = new Doc("root", DRAFT);
            onSubmit.behaviour = (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                if (ctx.entity().id().equals("root")) {
                    ctx.cascade(DOCS, peer, SUBMIT);
                    ctx.cascade(DOCS, peer, SEND_BACK);
                }
                return ActionOutcome.ok();
            };
            Outcome<Doc, DocStatus, DocTransition> outcome = docs.of(DOCS).execute(root, SUBMIT);
            assertInstanceOf(Outcome.Applied.class, outcome);
            Outcome.Applied<Doc, DocStatus, DocTransition> applied =
                    (Outcome.Applied<Doc, DocStatus, DocTransition>) outcome;
            assertEquals(List.of(), applied.reactions());
            assertEquals(3, applied.records().size());
            assertEquals(List.of("review:root", "review:peer", "draft:peer"), hooks);
            assertEquals(List.of("root:REVIEW", "peer:DRAFT"), writes);
            assertEquals(DRAFT, peer.status());
            assertEquals(PUBLISHED, observer.status());

            hooks.clear();
            writes.clear();
            Outcome<Doc, DocStatus, DocTransition> second = docs.of(DOCS).execute(peer, SUBMIT);
            assertInstanceOf(Outcome.Applied.class, second);
            Outcome.Applied<Doc, DocStatus, DocTransition> moved =
                    (Outcome.Applied<Doc, DocStatus, DocTransition>) second;
            assertEquals(1, moved.reactions().size());
            assertEquals(ARCHIVED, observer.status());
        }
    }

    /** A self-loop commits like any other edge and, moving nothing either, notifies no watch. */
    @Test
    void anAppliedSelfLoopNotifiesNoWatch() {
        Doc observer = new Doc("obs", PUBLISHED);
        Probe<Doc, DocStatus, DocTransition> onSubmit = new Probe<>();
        try (MachineRegistry docs = docsWatching(observer, "peer", onSubmit)) {
            Doc peer = new Doc("peer", REVIEW);
            Outcome<Doc, DocStatus, DocTransition> outcome = docs.of(DOCS).execute(peer, RETOUCH);
            assertInstanceOf(Outcome.Applied.class, outcome);
            Outcome.Applied<Doc, DocStatus, DocTransition> applied =
                    (Outcome.Applied<Doc, DocStatus, DocTransition>) outcome;
            assertEquals(List.of(), applied.reactions());
            assertEquals(1, applied.records().size());
            assertEquals(List.of("retouch:peer"), hooks);
            assertEquals(List.of("peer:REVIEW"), writes);
            assertEquals(REVIEW, peer.status());
            assertEquals(PUBLISHED, observer.status());

            Outcome.Applied<?, ?, ?> moved = assertInstanceOf(Outcome.Applied.class,
                    docs.of(DOCS).execute(peer, PUBLISH));
            assertEquals(1, moved.reactions().size());
            assertEquals(PUBLISHED, peer.status());
            assertEquals(ARCHIVED, observer.status());
        }
    }

    /** Hooks precede watches: one that throws leaves the state written and no watcher notified. */
    @Test
    void aHookThatThrowsCommitsTheStateAndNotifiesNoWatch() {
        Doc observer = new Doc("obs", PUBLISHED);
        Probe<Doc, DocStatus, DocTransition> onSubmit = new Probe<>();
        try (MachineRegistry docs = docsWatching(observer, "peer", onSubmit)) {
            Doc peer = new Doc("peer", DRAFT);
            failingHook = "review:peer";
            AfterCommitException error = assertThrows(AfterCommitException.class,
                    () -> docs.of(DOCS).execute(peer, SUBMIT));
            assertEquals("onReview", error.step());
            assertEquals(List.of("review:peer"), hooks);
            assertEquals(List.of("peer:REVIEW"), writes);
            assertEquals(REVIEW, peer.status());
            assertEquals(PUBLISHED, observer.status());
        }
    }

    /** A root that commits nothing notifies nothing: no setter, no hook and no watch behind a rejection. */
    @Test
    void aRootThatCommitsNothingNotifiesNoWatch() {
        Doc observer = new Doc("obs", PUBLISHED);
        Probe<Doc, DocStatus, DocTransition> onSubmit = new Probe<>();
        try (MachineRegistry docs = docsWatching(observer, "peer", onSubmit)) {
            Doc peer = new Doc("peer", DRAFT);
            assertInstanceOf(Outcome.Rejected.class, docs.of(DOCS).execute(peer, ARCHIVE));
            assertEquals(List.of(), writes);
            assertEquals(List.of(), hooks);
            assertEquals(DRAFT, peer.status());
            assertEquals(PUBLISHED, observer.status());
        }
    }

    /** The reaction commits in its own root: what it moves never joins the records of the root that triggered it. */
    @Test
    void aReactionCommitsInItsOwnRootAndNotInTheTriggeringSummary() {
        Order order = new Order("o1", OrderStatus.PAID);
        Item i1 = new Item("i1", ItemStatus.PACKING, Optional.of(order));
        Outcome<Item, ItemStatus, ItemTransition> outcome = items.execute(i1, ItemTransition.PACK);
        assertInstanceOf(Outcome.Applied.class, outcome);
        Outcome.Applied<Item, ItemStatus, ItemTransition> applied =
                (Outcome.Applied<Item, ItemStatus, ItemTransition>) outcome;
        assertEquals(List.of(ITEMS), keysOf(applied.records()));
        assertEquals(1, applied.reactions().size());
        Reaction.Done done = assertInstanceOf(Reaction.Done.class, applied.reactions().get(0));
        Outcome.Applied<?, ?, ?> shipped = (Outcome.Applied<?, ?, ?>) done.outcome();
        assertEquals(List.of(ORDERS), keysOf(shipped.records()));
        assertEquals(List.of(), shipped.reactions());
        assertEquals(OrderStatus.SHIPPED, order.status());
        assertEquals(ItemStatus.PACKED, i1.status());
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
