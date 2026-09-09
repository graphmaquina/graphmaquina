package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static io.github.graphmaquina.fixtures.Keys.ITEMS;
import static io.github.graphmaquina.fixtures.Keys.ORDERS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.CascadePolicy;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.error.AfterCommitException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.Item;
import io.github.graphmaquina.fixtures.ItemStatus;
import io.github.graphmaquina.fixtures.Order;
import io.github.graphmaquina.fixtures.OrderStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class HooksTest {

    final List<String> log = new ArrayList<>();

    private GraphDefinition<Doc, DocStatus> docs(Consumer<Doc> onReview, Consumer<Doc> onPublished, Consumer<Doc> onArchived) {
        return StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED).allowsCycle(REVIEW)
                .from(DRAFT).to(REVIEW).move().afterCommit("onReview", onReview).stay().forbidOthers("x")
                .from(REVIEW)
                    .to(PUBLISHED).action("publish", (TransitionContext<Doc, DocStatus> ctx) -> ActionOutcome.failed("not ready"))
                        .onFailure(DRAFT).afterCommit("onPublished", onPublished)
                    .to(DRAFT).move().afterCommit("onBackToDraft", (Doc doc) -> log.add("back:" + doc.id()))
                    .stay().forbidOthers("x")
                .from(PUBLISHED).to(ARCHIVED).move().afterCommit("onArchived", onArchived).stay().forbidOthers("x")
                .from(ARCHIVED).stay().forbidOthers("x")
                .build();
    }

    private StateMachine<Doc, DocStatus> machine(GraphDefinition<Doc, DocStatus> graph) {
        return StateMachines.from(graph).state(Doc::status, Doc::setStatus).build();
    }

    @Test
    void hookRunsAfterTheStateIsWritten() {
        StateMachine<Doc, DocStatus> docs = machine(docs(
                (Doc doc) -> log.add("review:" + doc.status()), (Doc doc) -> { }, (Doc doc) -> { }));
        Doc doc = new Doc("1", DRAFT);
        assertInstanceOf(Outcome.Applied.class, docs.transitionTo(doc, REVIEW));
        assertEquals(List.of("review:REVIEW"), log);
    }

    @Test
    void failureRouteRunsTheHookOfTheRouteCellNotTheOriginal() {
        StateMachine<Doc, DocStatus> docs = machine(docs(
                (Doc doc) -> { }, (Doc doc) -> log.add("published"), (Doc doc) -> { }));
        Doc doc = new Doc("1", REVIEW);
        assertInstanceOf(Outcome.Failed.class, docs.transitionTo(doc, PUBLISHED));
        assertEquals(DRAFT, doc.status());
        assertEquals(List.of("back:1"), log);
    }

    @Test
    void hookThatThrowsSurfacesAfterCommitExceptionAndKeepsTheState() {
        IllegalStateException boom = new IllegalStateException("ledger down");
        StateMachine<Doc, DocStatus> docs = machine(docs(
                (Doc doc) -> { throw boom; }, (Doc doc) -> { }, (Doc doc) -> { }));
        Doc doc = new Doc("1", DRAFT);
        AfterCommitException error = assertThrows(AfterCommitException.class, () -> docs.transitionTo(doc, REVIEW));
        assertSame(boom, error.getCause());
        assertEquals("onReview", error.step());
        assertEquals(DOCS, error.key());
        assertEquals(REVIEW, doc.status());
    }

    @Test
    void hookMayOpenANewRootTransaction() {
        List<StateMachine<Doc, DocStatus>> holder = new ArrayList<>();
        StateMachine<Doc, DocStatus> docs = machine(docs(
                (Doc doc) -> holder.get(0).transitionTo(doc, DRAFT), (Doc doc) -> { }, (Doc doc) -> { }));
        holder.add(docs);
        Doc doc = new Doc("1", DRAFT);
        Outcome<Doc, DocStatus> outcome = docs.transitionTo(doc, REVIEW);
        assertInstanceOf(Outcome.Applied.class, outcome);
        assertEquals(REVIEW, ((Outcome.Applied<Doc, DocStatus>) outcome).to());
        assertEquals(DRAFT, doc.status());
        assertEquals(List.of("back:1"), log);
    }

    @Test
    void noHookRunsOnRejectedOrIgnored() {
        StateMachine<Doc, DocStatus> docs = machine(docs(
                (Doc doc) -> log.add("review"), (Doc doc) -> log.add("published"), (Doc doc) -> log.add("archived")));
        Doc doc = new Doc("1", DRAFT);
        assertInstanceOf(Outcome.Rejected.class, docs.transitionTo(doc, ARCHIVED));
        assertInstanceOf(Outcome.Ignored.class, docs.transitionTo(doc, DRAFT));
        assertTrue(log.isEmpty());
    }

    @Test
    void cascadedCellsRunTheirHooksInLogOrderAtTheRootCommit() {
        GraphDefinition<Item, ItemStatus> items = StateGraph.define(ITEMS)
                .initial(ItemStatus.PENDING).terminal(ItemStatus.PACKED).terminal(ItemStatus.CANCELLED)
                .from(ItemStatus.PENDING).to(ItemStatus.PACKING).move()
                    .to(ItemStatus.CANCELLED).move().afterCommit("itemCancelled", (Item item) -> log.add("item:" + item.id()))
                    .stay().forbidOthers("x")
                .from(ItemStatus.PACKING).to(ItemStatus.PACKED).move().to(ItemStatus.CANCELLED).move().stay().forbidOthers("x")
                .from(ItemStatus.PACKED).stay().forbidOthers("x")
                .from(ItemStatus.CANCELLED).stay().forbidOthers("x")
                .build();
        GraphDefinition<Order, OrderStatus> orders = StateGraph.define(ORDERS)
                .initial(OrderStatus.NEW).terminal(OrderStatus.SHIPPED).terminal(OrderStatus.CANCELLED).terminal(OrderStatus.PAID)
                .terminal(OrderStatus.PAYMENT_DECLINED)
                .from(OrderStatus.NEW)
                    .to(OrderStatus.CANCELLED).action("cancel", (TransitionContext<Order, OrderStatus> ctx) -> {
                        ctx.cascadeAll(ITEMS, ctx.entity().items(), ItemStatus.CANCELLED, CascadePolicy.COLLECT);
                        log.add("action");
                        return ActionOutcome.ok();
                    }).afterCommit("orderCancelled", (Order order) -> log.add("order:" + order.id()))
                    .to(OrderStatus.PAID).move().to(OrderStatus.SHIPPED).move().to(OrderStatus.PAYMENT_DECLINED).move()
                    .stay().forbidOthers("x")
                .from(OrderStatus.PAID).stay().forbidOthers("x")
                .from(OrderStatus.PAYMENT_DECLINED).stay().forbidOthers("x")
                .from(OrderStatus.SHIPPED).stay().forbidOthers("x")
                .from(OrderStatus.CANCELLED).stay().forbidOthers("x")
                .build();
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(orders).state(Order::status, Order::setStatus).identity(Order::id))
                .add(StateMachines.from(items).state(Item::status, Item::setStatus).identity(Item::id))
                .build()) {
            Order order = new Order("o1", OrderStatus.NEW);
            new Item("i1", ItemStatus.PENDING, Optional.of(order));
            new Item("i2", ItemStatus.PENDING, Optional.of(order));
            assertInstanceOf(Outcome.Applied.class, registry.of(ORDERS).transitionTo(order, OrderStatus.CANCELLED));
            assertEquals(List.of("action", "order:o1", "item:i1", "item:i2"), log);
        }
    }
}
