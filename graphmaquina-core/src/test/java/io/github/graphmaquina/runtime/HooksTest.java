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
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.CascadePolicy;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.StatesWritten;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.error.AfterCommitException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import io.github.graphmaquina.fixtures.Item;
import io.github.graphmaquina.fixtures.ItemStatus;
import io.github.graphmaquina.fixtures.ItemTransition;
import io.github.graphmaquina.fixtures.Order;
import io.github.graphmaquina.fixtures.OrderStatus;
import io.github.graphmaquina.fixtures.OrderTransition;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * After-commit hooks declared with {@code afterCommit}: they run once the state is written and, for a cascade, in log
 * order at the root commit; a failure route runs the hook of the recovering edge; none runs on Rejected or Ignored; a
 * hook that throws surfaces an AfterCommitException and keeps the state; and a hook may open a new root transaction.
 */
class HooksTest {

    final List<String> log = new ArrayList<>();

    private GraphDefinition<Doc, DocStatus, DocTransition> docs(Consumer<Doc> onReview, Consumer<Doc> onPublished,
                                                                Consumer<Doc> onArchived) {
        return StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED).allowsCycle(REVIEW)
                .statePolicy(DRAFT, "a draft is submitted")
                .statePolicy(REVIEW, "in review it is published or sent back")
                .statePolicy(PUBLISHED, "published only archives")
                .transition(SUBMIT, DRAFT, REVIEW).afterCommit("onReview", onReview).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED)
                    .action("publish", (TransitionContext<Doc, DocStatus, DocTransition> ctx) ->
                            ActionOutcome.failed("not ready"))
                    .onFailure(SEND_BACK).afterCommit("onPublished", onPublished).endTransition()
                .transition(SEND_BACK, REVIEW, DRAFT)
                    .afterCommit("onBackToDraft", (Doc doc) -> log.add("back:" + doc.id())).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).afterCommit("onArchived", onArchived).endTransition()
                .build();
    }

    private StateMachine<Doc, DocStatus, DocTransition> machine(GraphDefinition<Doc, DocStatus, DocTransition> graph) {
        return StateMachines.from(graph).state(Doc::status, Doc::setStatus).build();
    }

    @Test
    void hookRunsAfterTheStateIsWritten() {
        StateMachine<Doc, DocStatus, DocTransition> docs = machine(docs(
                (Doc doc) -> log.add("review:" + doc.status()), (Doc doc) -> { }, (Doc doc) -> { }));
        Doc doc = new Doc("1", DRAFT);
        assertInstanceOf(Outcome.Applied.class, docs.execute(doc, SUBMIT));
        assertEquals(List.of("review:REVIEW"), log);
    }

    @Test
    void failureRouteRunsTheHookOfTheRecoveringEdgeNotTheOriginal() {
        StateMachine<Doc, DocStatus, DocTransition> docs = machine(docs(
                (Doc doc) -> { }, (Doc doc) -> log.add("published"), (Doc doc) -> { }));
        Doc doc = new Doc("1", REVIEW);
        assertInstanceOf(Outcome.Failed.class, docs.execute(doc, PUBLISH));
        assertEquals(DRAFT, doc.status());
        assertEquals(List.of("back:1"), log);
    }

    @Test
    void hookThatThrowsSurfacesAfterCommitExceptionAndKeepsTheState() {
        IllegalStateException boom = new IllegalStateException("ledger down");
        StateMachine<Doc, DocStatus, DocTransition> docs = machine(docs(
                (Doc doc) -> { throw boom; }, (Doc doc) -> { }, (Doc doc) -> { }));
        Doc doc = new Doc("1", DRAFT);
        AfterCommitException error = assertThrows(AfterCommitException.class, () -> docs.execute(doc, SUBMIT));
        assertSame(boom, error.getCause());
        assertEquals("onReview", error.hook());
        assertEquals(DOCS, error.hop().key());
        assertEquals(SUBMIT, error.hop().transition());
        assertEquals(CallPhase.HOOK, error.phase());
        assertEquals(StatesWritten.ALL, error.statesWritten());
        assertEquals(1, error.records().size());
        assertEquals(error.hop(), error.records().get(0));
        assertEquals(List.of(), error.journal());
        assertEquals(REVIEW, doc.status());
    }

    @Test
    void hookMayOpenANewRootTransaction() {
        List<StateMachine<Doc, DocStatus, DocTransition>> holder = new ArrayList<>();
        StateMachine<Doc, DocStatus, DocTransition> docs = machine(docs(
                (Doc doc) -> holder.get(0).execute(doc, SEND_BACK), (Doc doc) -> { }, (Doc doc) -> { }));
        holder.add(docs);
        Doc doc = new Doc("1", DRAFT);
        Outcome<Doc, DocStatus, DocTransition> outcome = docs.execute(doc, SUBMIT);
        assertInstanceOf(Outcome.Applied.class, outcome);
        assertEquals(REVIEW, ((Outcome.Applied<Doc, DocStatus, DocTransition>) outcome).to());
        assertEquals(DRAFT, doc.status());
        assertEquals(List.of("back:1"), log);
    }

    @Test
    void noHookRunsOnRejectedOrIgnored() {
        StateMachine<Doc, DocStatus, DocTransition> docs = machine(docs(
                (Doc doc) -> log.add("review"), (Doc doc) -> log.add("published"), (Doc doc) -> log.add("archived")));
        Doc doc = new Doc("1", DRAFT);
        assertInstanceOf(Outcome.Rejected.class, docs.execute(doc, ARCHIVE));
        assertInstanceOf(Outcome.Ignored.class, docs.reevaluate(doc));
        assertTrue(log.isEmpty());
    }

    @Test
    void cascadedEdgesRunTheirHooksInLogOrderAtTheRootCommit() {
        GraphDefinition<Item, ItemStatus, ItemTransition> items = StateGraph.define(ITEMS)
                .initial(ItemStatus.PENDING).terminal(ItemStatus.PACKED).terminal(ItemStatus.CANCELLED)
                .statePolicy(ItemStatus.PENDING, "an item starts packing first")
                .statePolicy(ItemStatus.PACKING, "cannot go back to pending")
                .transition(ItemTransition.START_PACKING, ItemStatus.PENDING, ItemStatus.PACKING).endTransition()
                .transition(ItemTransition.CANCEL, ItemStatus.PENDING, ItemStatus.CANCELLED)
                    .afterCommit("itemCancelled", (Item item) -> log.add("item:" + item.id())).endTransition()
                .transition(ItemTransition.PACK, ItemStatus.PACKING, ItemStatus.PACKED).endTransition()
                .transition(ItemTransition.CANCEL, ItemStatus.PACKING, ItemStatus.CANCELLED).endTransition()
                .build();
        GraphDefinition<Order, OrderStatus, OrderTransition> orders = StateGraph.define(ORDERS)
                .initial(OrderStatus.NEW).terminal(OrderStatus.SHIPPED).terminal(OrderStatus.CANCELLED)
                .terminal(OrderStatus.PAID).terminal(OrderStatus.PAYMENT_DECLINED)
                .statePolicy(OrderStatus.NEW, "a new order is charged or cancelled")
                .transition(OrderTransition.CANCEL, OrderStatus.NEW, OrderStatus.CANCELLED)
                    .action("cancel", (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> {
                        ctx.cascadeAll(ITEMS, ctx.entity().items(), ItemTransition.CANCEL, CascadePolicy.COLLECT);
                        log.add("action");
                        return ActionOutcome.ok();
                    }).afterCommit("orderCancelled", (Order order) -> log.add("order:" + order.id())).endTransition()
                .transition(OrderTransition.CHARGE, OrderStatus.NEW, OrderStatus.PAID).endTransition()
                .transition(OrderTransition.SHIP, OrderStatus.NEW, OrderStatus.SHIPPED).endTransition()
                .transition(OrderTransition.DECLINE, OrderStatus.NEW, OrderStatus.PAYMENT_DECLINED).endTransition()
                .build();
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(orders).state(Order::status, Order::setStatus).identity(Order::id))
                .add(StateMachines.from(items).state(Item::status, Item::setStatus).identity(Item::id))
                .build()) {
            Order order = new Order("o1", OrderStatus.NEW);
            new Item("i1", ItemStatus.PENDING, Optional.of(order));
            new Item("i2", ItemStatus.PENDING, Optional.of(order));
            assertInstanceOf(Outcome.Applied.class, registry.of(ORDERS).execute(order, OrderTransition.CANCEL));
            assertEquals(List.of("action", "order:o1", "item:i1", "item:i2"), log);
        }
    }
}
