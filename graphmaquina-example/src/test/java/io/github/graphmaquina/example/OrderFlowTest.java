package io.github.graphmaquina.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionRecord;
import io.github.graphmaquina.api.ActionStatus;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.Reaction;
import io.github.graphmaquina.error.CascadeException;
import io.github.graphmaquina.example.item.Item;
import io.github.graphmaquina.example.item.ItemActions;
import io.github.graphmaquina.example.item.ItemStatus;
import io.github.graphmaquina.example.item.Items;
import io.github.graphmaquina.example.order.Order;
import io.github.graphmaquina.example.order.OrderActions;
import io.github.graphmaquina.example.order.OrderStatus;
import io.github.graphmaquina.example.order.Orders;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OrderFlowTest {

    final FakeGateway gateway = new FakeGateway();
    final FakeCarrier carrier = new FakeCarrier();
    final List<String> outcomes = new ArrayList<>();
    final ShopMachines shop = ShopMachinesRuntime.builder(Duration.ofSeconds(10), new OrderActions(gateway, carrier), new ItemActions())
            .listener((MachineKey<?, ?> key, Outcome<?, ?> outcome) ->
                    outcomes.add(key.entity().getSimpleName() + ":" + outcome.getClass().getSimpleName()))
            .build();
    final Orders orders = shop.orders();
    final Items items = shop.items();

    @AfterEach
    void close() {
        shop.close();
    }

    @Test
    void happyPathShipsWhenTheLastItemIsPacked() {
        Order order = new Order("o1");
        Item i1 = new Item("i1", Optional.of(order));
        Item i2 = new Item("i2", Optional.of(order));
        gateway.accept();

        assertInstanceOf(Outcome.Applied.class, orders.pay(order));
        assertEquals(OrderStatus.PAID, order.status());
        assertEquals(1, order.paymentAttempts());

        items.startPacking(i1);
        items.startPacking(i2);
        Outcome<Item, ItemStatus> firstOutcome = items.pack(i1);
        assertInstanceOf(Outcome.Applied.class, firstOutcome);
        Outcome.Applied<Item, ItemStatus> first = (Outcome.Applied<Item, ItemStatus>) firstOutcome;
        Reaction.Done notYet = assertInstanceOf(Reaction.Done.class, first.reactions().get(0));
        assertInstanceOf(Outcome.Ignored.class, notYet.outcome());
        assertEquals(OrderStatus.PAID, order.status());
        assertTrue(carrier.notified.isEmpty());

        Outcome<Item, ItemStatus> lastOutcome = items.pack(i2);
        assertInstanceOf(Outcome.Applied.class, lastOutcome);
        Outcome.Applied<Item, ItemStatus> last = (Outcome.Applied<Item, ItemStatus>) lastOutcome;
        Reaction.Done shipped = assertInstanceOf(Reaction.Done.class, last.reactions().get(0));
        Outcome.Applied<?, ?> reaction = assertInstanceOf(Outcome.Applied.class, shipped.outcome());
        assertEquals(OrderStatus.SHIPPED, reaction.to());
        assertEquals(OrderStatus.SHIPPED, order.status());
        assertEquals(List.of("o1:SHIPPED"), carrier.notified);
        assertEquals(List.of("Order:Applied", "Order:Ignored", "Item:Applied", "Order:Ignored", "Item:Applied", "Order:Ignored", "Item:Applied",
                        "Order:Applied", "Item:Applied"),
                outcomes);
    }

    @Test
    void threeDeclinesExhaustTheRetryBudget() {
        Order order = new Order("o1");
        new Item("i1", Optional.of(order));
        gateway.decline("51").decline("51").decline("05");

        for (int attempt = 1; attempt <= 3; attempt++) {
            Outcome<Order, OrderStatus> outcome = orders.pay(order);
            assertInstanceOf(Outcome.Failed.class, outcome);
            Outcome.Failed<Order, OrderStatus> failed = (Outcome.Failed<Order, OrderStatus>) outcome;
            assertEquals(OrderStatus.PAYMENT_DECLINED, failed.movedTo());
            assertEquals(OrderStatus.PAYMENT_DECLINED, order.status());
            if (attempt < 3) {
                assertInstanceOf(Outcome.Applied.class, orders.retry(order));
            }
        }
        Outcome<Order, OrderStatus> outcome = orders.retry(order);
        assertInstanceOf(Outcome.Rejected.class, outcome);
        Outcome.Rejected<Order, OrderStatus> rejected = (Outcome.Rejected<Order, OrderStatus>) outcome;
        assertEquals(Optional.of("retryBudgetLeft"), rejected.guard());
        assertInstanceOf(Outcome.Applied.class, orders.cancel(order));
        assertEquals(OrderStatus.CANCELLED, order.status());
        assertEquals(ItemStatus.CANCELLED, order.items().get(0).status());
    }

    @Test
    void gatewayIOExceptionIsADomainFailure() {
        Order order = new Order("o1");
        gateway.fail(new IOException("gateway down"));
        Outcome<Order, OrderStatus> outcome = orders.pay(order);
        assertInstanceOf(Outcome.Failed.class, outcome);
        Outcome.Failed<Order, OrderStatus> failed = (Outcome.Failed<Order, OrderStatus>) outcome;
        assertInstanceOf(IOException.class, failed.cause().orElseThrow());
        assertEquals(OrderStatus.PAYMENT_DECLINED, order.status());
    }

    @Test
    void cancelFromNewCascadesToItems() {
        Order order = new Order("o1");
        Item i1 = new Item("i1", Optional.of(order));
        Item i2 = new Item("i2", Optional.of(order));
        Outcome<Order, OrderStatus> outcome = orders.cancel(order);
        assertInstanceOf(Outcome.Applied.class, outcome);
        Outcome.Applied<Order, OrderStatus> applied = (Outcome.Applied<Order, OrderStatus>) outcome;
        assertEquals(3, applied.records().size());
        assertEquals(OrderStatus.CANCELLED, order.status());
        assertEquals(ItemStatus.CANCELLED, i1.status());
        assertEquals(ItemStatus.CANCELLED, i2.status());
    }

    @Test
    void cancelWithAPackedItemAbortsAndReportsTheJournal() {
        Order order = new Order("o1");
        Item packed = new Item("i1", Optional.of(order));
        Item pending = new Item("i2", Optional.of(order));
        items.startPacking(packed);
        items.pack(packed);

        CascadeException error = assertThrows(CascadeException.class, () -> orders.cancel(order));
        assertEquals(1, error.errors().size());
        assertEquals(List.of("cancel"), error.journal().stream().map(ActionRecord::action).toList());
        assertEquals(ActionStatus.THREW, error.journal().get(0).status());
        assertEquals(OrderStatus.NEW, order.status());
        assertEquals(ItemStatus.PACKED, packed.status());
        assertEquals(ItemStatus.PENDING, pending.status());
    }

    @Test
    void operationsThatDoNotApplyAreRejectedWithTheRowReason() {
        Order order = new Order("o1");
        Outcome<Order, OrderStatus> outcome = orders.ship(order);
        assertInstanceOf(Outcome.Rejected.class, outcome);
        Outcome.Rejected<Order, OrderStatus> rejected = (Outcome.Rejected<Order, OrderStatus>) outcome;
        assertEquals("cannot ship without payment", rejected.reason());
        assertEquals(Optional.empty(), rejected.guard());
    }
}
