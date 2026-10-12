package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.Keys.ITEMS;
import static io.github.graphmaquina.fixtures.Keys.ORDERS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.ActionRecord;
import io.github.graphmaquina.api.ActionStatus;
import io.github.graphmaquina.api.CascadePolicy;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.error.CascadeException;
import io.github.graphmaquina.error.TransitionAbortedException;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The journals that the aborts COLLECT gathers carry: each abort's journal is the call's journal as it stood when that
 * element failed, the in-flight root action marked THREW, the earlier elements and their nested cascades with their
 * final statuses and the failed element's own entries, and it never changes afterwards nor accepts a change. A COLLECT
 * over many failing elements keeps those journals in memory linear in the number of elements.
 */
class CollectJournalTest {

    final Probe<Order, OrderStatus, OrderTransition> shipOrder = new Probe<>();
    final Probe<Item, ItemStatus, ItemTransition> packItem = new Probe<>();

    private MachineRegistry registry() {
        return MachineRegistry.builder()
                .add(StateMachines.from(OrderGraphs.order(
                                (TransitionContext<Order, OrderStatus, OrderTransition> ctx) ->
                                        CompletableFuture.completedFuture(ActionOutcome.ok()),
                                shipOrder, new Probe<>(), new GuardProbe<>(), new GuardProbe<>()))
                        .state(Order::status, Order::setStatus).identity(Order::id).asyncTimeout(Duration.ofSeconds(5)))
                .add(StateMachines.from(OrderGraphs.item(packItem))
                        .state(Item::status, Item::setStatus).identity(Item::id))
                .build();
    }

    /** Each entry as {@code action:entity:status}. */
    private static List<String> entries(List<ActionRecord> journal) {
        List<String> entries = new ArrayList<>();
        for (ActionRecord record : journal) {
            entries.add(record.action() + ":" + record.entity() + ":" + record.status());
        }
        return entries;
    }

    private static void assertUnmodifiable(List<ActionRecord> journal) {
        ActionRecord first = journal.get(0);
        assertThrows(UnsupportedOperationException.class, () -> journal.add(first));
        assertThrows(UnsupportedOperationException.class, () -> journal.set(0, first));
        assertThrows(UnsupportedOperationException.class, () -> journal.remove(0));
        assertThrows(UnsupportedOperationException.class, journal::clear);
        assertThrows(UnsupportedOperationException.class, () -> journal.addAll(List.of()));
        assertThrows(UnsupportedOperationException.class,
                () -> journal.removeIf((ActionRecord record) -> false));
    }

    /**
     * COLLECT over an element whose action cascades and succeeds, a failing one, a succeeding one and one that
     * cascades to a grandchild before it fails: the first abort's journal stops at its own element, the second's and
     * the aggregate's hold every action of the call, and the first abort's journal read inside the root action is
     * the one read once the call ended.
     */
    @Test
    void eachCollectedAbortCarriesTheJournalAsItStoodWhenItsElementFailed() {
        Order order = new Order("o1", OrderStatus.PAID);
        Item a = new Item("a", ItemStatus.PACKING, Optional.empty());
        Item b = new Item("b", ItemStatus.PACKING, Optional.empty());
        Item c = new Item("c", ItemStatus.PACKING, Optional.empty());
        Item d = new Item("d", ItemStatus.PACKING, Optional.empty());
        Item nested = new Item("x", ItemStatus.PACKING, Optional.empty());
        Item grandchild = new Item("g", ItemStatus.PACKING, Optional.empty());
        AtomicReference<CascadeException> caught = new AtomicReference<>();
        AtomicReference<List<String>> readInside = new AtomicReference<>();
        shipOrder.behaviour = (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> {
            try {
                ctx.cascadeAll(ITEMS, List.of(a, b, c, d), ItemTransition.PACK, CascadePolicy.COLLECT);
            } catch (CascadeException e) {
                caught.set(e);
                readInside.set(entries(e.errors().get(0).journal()));
                throw e;
            }
            return ActionOutcome.ok();
        };
        packItem.behaviour = (TransitionContext<Item, ItemStatus, ItemTransition> ctx) -> {
            switch (ctx.entity().id()) {
                case "a" -> ctx.cascade(ITEMS, nested, ItemTransition.PACK);
                case "b" -> throw new IllegalStateException("b cannot be packed");
                case "d" -> {
                    ctx.cascade(ITEMS, grandchild, ItemTransition.PACK);
                    throw new IllegalStateException("d cannot be packed");
                }
                default -> { }
            }
            return ActionOutcome.ok();
        };
        CascadeException error;
        try (MachineRegistry r = registry()) {
            error = assertThrows(CascadeException.class, () -> r.of(ORDERS).execute(order, OrderTransition.SHIP));
        }
        assertSame(caught.get(), error);
        assertEquals(2, error.errors().size());
        List<String> first = List.of("ShipOrder:Order#o1:THREW", "PackItem:Item#a:OK", "PackItem:Item#x:OK",
                "PackItem:Item#b:THREW");
        List<String> all = List.of("ShipOrder:Order#o1:THREW", "PackItem:Item#a:OK", "PackItem:Item#x:OK",
                "PackItem:Item#b:THREW", "PackItem:Item#c:OK", "PackItem:Item#d:THREW", "PackItem:Item#g:OK");
        assertEquals(first, entries(error.errors().get(0).journal()));
        assertEquals(all, entries(error.errors().get(1).journal()));
        assertEquals(all, entries(error.journal()));
        assertEquals(first, readInside.get());
        for (TransitionAbortedException abort : error.errors()) {
            assertUnmodifiable(abort.journal());
        }
        assertUnmodifiable(error.journal());
        assertEquals(error.journal(), error.errors().get(1).journal());
        assertEquals(error.journal().hashCode(), error.errors().get(1).journal().hashCode());
        assertEquals(error.journal().toString(), List.copyOf(error.journal()).toString());
    }

    /**
     * Every abort of a COLLECT over 30,000 failing elements carries the journal as it stood when its element failed:
     * the root action and every element so far, 450,045,000 entries in all. A separate copy per abort would hold as
     * many references, about 1.8 GB, far more than the test heap; the journals share their entries instead.
     */
    @Test
    void collectOverManyFailingElementsKeepsTheirJournalsInLinearMemory() {
        int count = 30_000;
        Order order = new Order("o1", OrderStatus.PAID);
        List<Item> items = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            items.add(new Item("i" + index, ItemStatus.PACKING, Optional.empty()));
        }
        IllegalStateException down = new IllegalStateException("inventory service down");
        shipOrder.behaviour = (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> {
            ctx.cascadeAll(ITEMS, items, ItemTransition.PACK, CascadePolicy.COLLECT);
            return ActionOutcome.ok();
        };
        packItem.behaviour = (TransitionContext<Item, ItemStatus, ItemTransition> ctx) -> {
            throw down;
        };
        CascadeException error;
        try (MachineRegistry r = registry()) {
            error = assertThrows(CascadeException.class, () -> r.of(ORDERS).execute(order, OrderTransition.SHIP));
        }
        List<TransitionAbortedException> errors = error.errors();
        assertEquals(count, errors.size());
        long total = 0;
        for (int index = 0; index < count; index++) {
            List<ActionRecord> journal = errors.get(index).journal();
            assertEquals(index + 2, journal.size());
            assertEquals(items.get(index), journal.get(index + 1).entity());
            assertEquals(ActionStatus.THREW, journal.get(index + 1).status());
            total += journal.size();
        }
        assertEquals(450_045_000L, total);
        assertEquals(count + 1, error.journal().size());
        assertEquals(order, error.journal().get(0).entity());
        assertEquals(ActionStatus.THREW, error.journal().get(0).status());
    }
}
