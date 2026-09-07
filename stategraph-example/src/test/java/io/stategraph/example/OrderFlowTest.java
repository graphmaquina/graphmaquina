package io.stategraph.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.stategraph.api.ActionRecord;
import io.stategraph.api.ActionStatus;
import io.stategraph.api.Outcome;
import io.stategraph.api.Reaction;
import io.stategraph.api.StateMachine;
import io.stategraph.error.CascadeException;
import io.stategraph.example.item.Item;
import io.stategraph.example.item.ItemStatus;
import io.stategraph.example.order.Order;
import io.stategraph.example.order.OrderStatus;
import io.stategraph.runtime.MachineRegistry;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OrderFlowTest {

    final FakeGateway gateway = new FakeGateway();
    final MachineRegistry registry = Machines.registry(gateway);
    final StateMachine<Order, OrderStatus> orders = registry.of(Machines.ORDERS);
    final StateMachine<Item, ItemStatus> items = registry.of(Machines.ITEMS);

    @AfterEach
    void close() {
        registry.close();
    }

    @Test
    void happyPathShipsWhenTheLastItemIsPacked() {
        Order order = new Order("o1");
        Item i1 = new Item("i1", Optional.of(order));
        Item i2 = new Item("i2", Optional.of(order));
        gateway.accept();

        assertInstanceOf(Outcome.Applied.class, orders.transitionTo(order, OrderStatus.PAID));
        assertEquals(OrderStatus.PAID, order.status());
        assertEquals(1, order.paymentAttempts());

        items.transitionTo(i1, ItemStatus.PACKING);
        items.transitionTo(i2, ItemStatus.PACKING);
        Outcome<Item, ItemStatus> firstOutcome = items.transitionTo(i1, ItemStatus.PACKED);
        assertInstanceOf(Outcome.Applied.class, firstOutcome);
        Outcome.Applied<Item, ItemStatus> first = (Outcome.Applied<Item, ItemStatus>) firstOutcome;
        Reaction.Done notYet = assertInstanceOf(Reaction.Done.class, first.reactions().get(0));
        assertInstanceOf(Outcome.Ignored.class, notYet.outcome());
        assertEquals(OrderStatus.PAID, order.status());

        Outcome<Item, ItemStatus> lastOutcome = items.transitionTo(i2, ItemStatus.PACKED);
        assertInstanceOf(Outcome.Applied.class, lastOutcome);
        Outcome.Applied<Item, ItemStatus> last = (Outcome.Applied<Item, ItemStatus>) lastOutcome;
        Reaction.Done shipped = assertInstanceOf(Reaction.Done.class, last.reactions().get(0));
        assertInstanceOf(Outcome.Applied.class, shipped.outcome());
        Outcome.Applied<?, ?> reaction = (Outcome.Applied<?, ?>) shipped.outcome();
        assertEquals(OrderStatus.SHIPPED, reaction.to());
        assertEquals(OrderStatus.SHIPPED, order.status());
    }

    @Test
    void threeDeclinesExhaustTheRetryBudget() {
        Order order = new Order("o1");
        new Item("i1", Optional.of(order));
        gateway.decline("51").decline("51").decline("05");

        for (int attempt = 1; attempt <= 3; attempt++) {
            Outcome<Order, OrderStatus> outcome = orders.transitionTo(order, OrderStatus.PAID);
            assertInstanceOf(Outcome.Failed.class, outcome);
            Outcome.Failed<Order, OrderStatus> failed = (Outcome.Failed<Order, OrderStatus>) outcome;
            assertEquals(OrderStatus.PAYMENT_DECLINED, failed.movedTo());
            assertEquals(OrderStatus.PAYMENT_DECLINED, order.status());
            if (attempt < 3) {
                assertInstanceOf(Outcome.Applied.class, orders.transitionTo(order, OrderStatus.NEW));
            }
        }
        Outcome<Order, OrderStatus> outcome = orders.transitionTo(order, OrderStatus.NEW);
        assertInstanceOf(Outcome.Rejected.class, outcome);
        Outcome.Rejected<Order, OrderStatus> rejected = (Outcome.Rejected<Order, OrderStatus>) outcome;
        assertEquals(Optional.of("RetryBudgetLeft"), rejected.guard());
        assertInstanceOf(Outcome.Applied.class, orders.transitionTo(order, OrderStatus.CANCELLED));
        assertEquals(OrderStatus.CANCELLED, order.status());
        assertEquals(ItemStatus.CANCELLED, order.items().get(0).status());
    }

    @Test
    void gatewayIOExceptionIsADomainFailure() {
        Order order = new Order("o1");
        gateway.fail(new IOException("gateway down"));
        Outcome<Order, OrderStatus> outcome = orders.transitionTo(order, OrderStatus.PAID);
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
        Outcome<Order, OrderStatus> outcome = orders.transitionTo(order, OrderStatus.CANCELLED);
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
        items.transitionTo(packed, ItemStatus.PACKING);
        items.transitionTo(packed, ItemStatus.PACKED);

        CascadeException error = assertThrows(CascadeException.class, () -> orders.transitionTo(order, OrderStatus.CANCELLED));
        assertEquals(1, error.errors().size());
        assertEquals(List.of("CancelOrder"), error.journal().stream().map(ActionRecord::action).toList());
        assertEquals(ActionStatus.THREW, error.journal().get(0).status());
        assertEquals(OrderStatus.NEW, order.status());
        assertEquals(ItemStatus.PACKED, packed.status());
        assertEquals(ItemStatus.PENDING, pending.status());
    }
}
