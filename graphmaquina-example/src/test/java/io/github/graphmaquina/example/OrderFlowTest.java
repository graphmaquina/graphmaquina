package io.github.graphmaquina.example;

import static io.github.graphmaquina.api.OutcomeStatus.APPLIED;
import static io.github.graphmaquina.api.OutcomeStatus.IGNORED;
import static io.github.graphmaquina.test.GraphAssertions.assertThat;
import static io.github.graphmaquina.test.GraphAssertions.captureFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionStatus;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.StatesWritten;
import io.github.graphmaquina.example.item.Item;
import io.github.graphmaquina.example.item.ItemActions;
import io.github.graphmaquina.example.item.ItemStatus;
import io.github.graphmaquina.example.item.Items;
import io.github.graphmaquina.example.item.ItemsRuntime;
import io.github.graphmaquina.example.order.Order;
import io.github.graphmaquina.example.order.OrderActions;
import io.github.graphmaquina.example.order.OrderStatus;
import io.github.graphmaquina.example.order.OrderTransition;
import io.github.graphmaquina.example.order.Orders;
import io.github.graphmaquina.example.order.OrdersRuntime;
import io.github.graphmaquina.test.TransitionRecorder;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The shop's business rules, asserted through the public testing API instead of by hand. */
class OrderFlowTest {

    final FakeGateway gateway = new FakeGateway();
    final FakeCarrier carrier = new FakeCarrier();
    final TransitionRecorder recorder = TransitionRecorder.create();
    final ShopMachines shop = ShopMachinesRuntime.builder(Duration.ofSeconds(10), new OrderActions(gateway, carrier),
                    new ItemActions())
            .observer(recorder)
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

        assertThat(orders.pay(order)).isApplied().movedTo(OrderStatus.PAID)
                .hasEntityState(Order::status, OrderStatus.PAID);
        assertEquals(1, order.paymentAttempts());

        items.startPacking(i1);
        items.startPacking(i2);
        assertThat(items.pack(i1)).isApplied().reactions()
                .forEntity(OrdersRuntime.KEY, order).single().isDone().outcome()
                .isIgnored().hasEntityState(Order::status, OrderStatus.PAID);
        assertTrue(carrier.notified.isEmpty());

        assertThat(items.pack(i2)).isApplied().reactions()
                .forEntity(OrdersRuntime.KEY, order).single().isDone().outcome()
                .isApplied().movedTo(OrderStatus.SHIPPED)
                .hasEntityState(Order::status, OrderStatus.SHIPPED);
        assertEquals(List.of("o1:SHIPPED:closed"), carrier.notified);

        recorder.assertRecorded()
                .hasSize(9)
                .hasKeysExactly(OrdersRuntime.KEY, OrdersRuntime.KEY, ItemsRuntime.KEY, OrdersRuntime.KEY,
                        ItemsRuntime.KEY, OrdersRuntime.KEY, ItemsRuntime.KEY, OrdersRuntime.KEY,
                        ItemsRuntime.KEY)
                .hasStatusesExactly(APPLIED, IGNORED, APPLIED, IGNORED, APPLIED, IGNORED, APPLIED, APPLIED, APPLIED);
    }

    @Test
    void threeDeclinesExhaustTheRetryBudget() {
        Order order = new Order("o1");
        new Item("i1", Optional.of(order));
        gateway.decline("51").decline("51").decline("05");

        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(orders.pay(order)).isFailed().movedTo(OrderStatus.PAYMENT_DECLINED)
                    .hasFailureTransition(OrderTransition.PAYMENT_DECLINED)
                    .hasEntityState(Order::status, OrderStatus.PAYMENT_DECLINED);
            if (attempt < 3) {
                assertThat(orders.retry(order)).isApplied().movedTo(OrderStatus.NEW);
            }
        }
        assertThat(orders.retry(order)).isRejected().isRejectedByGuardNamed("retryBudgetLeft");
        assertThat(orders.cancel(order)).isApplied()
                .hasEntityState(Order::status, OrderStatus.CANCELLED);
        assertThat(order.items().get(0), Item::status).isIn(ItemStatus.CANCELLED);
    }

    @Test
    void gatewayIOExceptionIsADomainFailure() {
        Order order = new Order("o1");
        IOException down = new IOException("gateway down");
        gateway.fail(down);

        assertThat(orders.pay(order)).isFailed().hasCauseSameAs(down)
                .hasEntityState(Order::status, OrderStatus.PAYMENT_DECLINED);
    }

    @Test
    void cancelFromNewCascadesToItems() {
        Order order = new Order("o1");
        Item i1 = new Item("i1", Optional.of(order));
        Item i2 = new Item("i2", Optional.of(order));

        assertThat(orders.cancel(order)).isApplied()
                .hasEntityState(Order::status, OrderStatus.CANCELLED)
                .records().hasSize(3);
        assertThat(i1, Item::status).isIn(ItemStatus.CANCELLED);
        assertThat(i2, Item::status).isIn(ItemStatus.CANCELLED);
    }

    @Test
    void cancelWithAPackedItemAbortsAndReportsTheJournal() {
        Order order = new Order("o1");
        Item packed = new Item("i1", Optional.of(order));
        Item pending = new Item("i2", Optional.of(order));
        items.startPacking(packed);
        items.pack(packed);

        assertThat(captureFailure(() -> orders.cancel(order))).isCascadeAbort()
                .hasErrorCount(1).hasPhase(CallPhase.ACTION).hasStatesWritten(StatesWritten.NONE)
                .journal().hasActionsExactly("cancel").hasStatusesExactly(ActionStatus.THREW);
        assertThat(order, Order::status).isIn(OrderStatus.NEW);
        assertThat(packed, Item::status).isIn(ItemStatus.PACKED);
        assertThat(pending, Item::status).isIn(ItemStatus.PENDING);
    }

    /** No edge carries SHIP from NEW, so the node's policy answers and nothing else is attempted. */
    @Test
    void anIdentifierWithNoEdgeFromTheCurrentStateIsRejectedByTheNodePolicy() {
        Order order = new Order("o1");

        assertThat(orders.ship(order)).isRejected()
                .hasReason("cannot ship without payment")
                .hasNoTransition()
                .requestedTransition(OrderTransition.SHIP);
        assertThat(order, Order::status).isIn(OrderStatus.NEW);
    }

    /**
     * PACK is the only way an item ever reaches PACKED, and it leaves PACKING, not PENDING, whose edges are
     * START_PACKING and CANCEL. The engine still refuses PACK from PENDING, with the policy's reason, rather than
     * taking an edge that happens to be available.
     */
    @Test
    void anAbsentIdentifierIsNeverServedByTheOtherEdgeLeavingTheSameState() {
        Item item = new Item("i1", Optional.empty());

        assertThat(items.pack(item)).isRejected()
                .hasReason("an item is packed by going through PACKING")
                .hasNoTransition();
        assertThat(item, Item::status).isIn(ItemStatus.PENDING);
    }
}
