package io.github.graphmaquina.example;

import static io.github.graphmaquina.test.GraphAssertions.assertThat;
import static io.github.graphmaquina.test.GraphAssertions.captureFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.graphmaquina.example.item.Item;
import io.github.graphmaquina.example.item.ItemActions;
import io.github.graphmaquina.example.item.ItemStatus;
import io.github.graphmaquina.example.item.ItemTransition;
import io.github.graphmaquina.example.item.Items;
import io.github.graphmaquina.example.item.ItemsRuntime;
import io.github.graphmaquina.example.order.Order;
import io.github.graphmaquina.example.order.OrderActions;
import io.github.graphmaquina.example.order.OrderStatus;
import io.github.graphmaquina.example.order.OrderTransition;
import io.github.graphmaquina.example.order.Orders;
import io.github.graphmaquina.example.order.OrdersRuntime;
import io.github.graphmaquina.test.GuardExpectation;
import io.github.graphmaquina.test.StateChange;
import io.github.graphmaquina.test.StateSnapshot;
import io.github.graphmaquina.test.TransitionRecorder;
import io.github.graphmaquina.test.WatchRuleExpectation;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * What the flow tests do not reach: the metadata both graphs of the example declare, the entities an aborted cascade
 * has to leave where they were, and the recorder's promise that a reaction it records as a call of its own is not also
 * counted a second time. Every expected name here is the runtime name the generated companion declares, not the name of
 * the method that implements it.
 */
class TestingApiTest {

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
    void theOrderGraphDeclaresItsShapeAndItsWatch() {
        assertThat(orders.definition())
                .hasInitial(OrderStatus.NEW)
                .hasTerminalsExactly(OrderStatus.SHIPPED, OrderStatus.CANCELLED)
                .hasAsync()
                .hasTransitionsExactly(OrderStatus.NEW,
                        OrderTransition.PAYMENT_DECLINED, OrderTransition.PAY, OrderTransition.CANCEL)
                .hasTransitionsExactly(OrderStatus.PAYMENT_DECLINED,
                        OrderTransition.RETRY, OrderTransition.CANCEL)
                .watch(OrderStatus.PAID)
                .hasPeer(ItemsRuntime.KEY)
                .hasRulesExactly(List.of(new WatchRuleExpectation<>("allIn PACKED", OrderTransition.SHIP)));
    }

    /** A terminal declares no edge at all, and its policy is what answers every identifier. */
    @Test
    void terminalStatesDeclareNoEdgeAndAnswerFromTheirPolicy() {
        assertThat(orders.definition())
                .hasTransitionsExactly(OrderStatus.SHIPPED)
                .hasNoTransition(OrderStatus.SHIPPED, OrderTransition.CANCEL)
                .policy(OrderStatus.SHIPPED)
                .hasDefaultReason("a shipment cannot be undone");
    }

    @Test
    void thePaymentEdgeCarriesItsTimeoutFailureRouteAndDeclaredFailure() {
        assertThat(orders.definition())
                .transition(OrderStatus.NEW, OrderTransition.PAY)
                .hasTarget(OrderStatus.PAID)
                .isAction()
                .hasActionNamed("pay")
                .hasAsyncAction()
                .hasGuardsExactly(new GuardExpectation("retryBudgetLeft", false))
                .hasTimeout(Duration.ofSeconds(5))
                .hasFailureTransition(OrderTransition.PAYMENT_DECLINED)
                .failsOnExactly(IOException.class)
                .hasNoHook();
    }

    @Test
    void theShippingEdgeRunsItsAfterCommitHook() {
        assertThat(orders.definition())
                .transition(OrderStatus.PAID, OrderTransition.SHIP)
                .hasTarget(OrderStatus.SHIPPED)
                .isAction()
                .hasActionNamed("ship")
                .hasSyncAction()
                .hasGuardsExactly(new GuardExpectation("allItemsPacked", false))
                .hasHookNamed("notifyCarrier")
                .hasNoTimeout()
                .hasNoFailureTransition()
                .failsOnExactly();
    }

    @Test
    void theItemGraphPacksOnlyThroughPacking() {
        assertThat(items.definition())
                .hasInitial(ItemStatus.PENDING)
                .hasTerminalsExactly(ItemStatus.PACKED, ItemStatus.CANCELLED)
                .hasNoAsync()
                .hasNoWatch(ItemStatus.PACKING)
                .transition(ItemStatus.PACKING, ItemTransition.PACK)
                .hasTarget(ItemStatus.PACKED)
                .isAction()
                .hasActionNamed("pack")
                .hasSyncAction()
                .hasNoGuards()
                .hasNoHook();
        assertThat(items.definition())
                .hasNoTransition(ItemStatus.PENDING, ItemTransition.PACK)
                .policy(ItemStatus.PENDING)
                .hasDefaultReason("an item is packed by going through PACKING");
    }

    /** One identifier leaves two different states; the current state, not the destination, disambiguates. */
    @Test
    void oneIdentifierLeavesEveryStateItWasDeclaredFrom() {
        assertThat(items.definition())
                .transition(ItemStatus.PENDING, ItemTransition.CANCEL)
                .hasTarget(ItemStatus.CANCELLED)
                .isAction().hasActionNamed("restock").hasHookNamed("applyRestock");
        assertThat(items.definition())
                .transition(ItemStatus.PACKING, ItemTransition.CANCEL)
                .hasTarget(ItemStatus.CANCELLED)
                .isAction().hasActionNamed("restock").hasHookNamed("applyRestock");
    }

    @Test
    void anAbortedCancellationLeavesEveryTrackedEntityWhereItWas() {
        Order order = new Order("o1");
        Item packed = new Item("i1", Optional.of(order));
        Item pending = new Item("i2", Optional.of(order));
        items.startPacking(packed);
        items.pack(packed);
        StateSnapshot before = track(order, packed, pending);

        assertThat(captureFailure(() -> orders.cancel(order, "B-7"))).isCascadeAbort().hasErrorCount(1);

        before.assertUnchanged();
    }

    /** The positive control of the test above: the same three trackers do report a cancellation that commits. */
    @Test
    void aCancellationThatCommitsIsReportedForEveryTrackedEntity() {
        Order order = new Order("o1");
        Item i1 = new Item("i1", Optional.of(order));
        Item i2 = new Item("i2", Optional.of(order));
        StateSnapshot before = track(order, i1, i2);

        assertThat(orders.cancel(order, "B-7")).isApplied().movedTo(OrderStatus.CANCELLED);

        assertEquals(List.of(
                        new StateChange(OrdersRuntime.KEY, order, Optional.of(OrderStatus.NEW),
                                Optional.of(OrderStatus.CANCELLED)),
                        new StateChange(ItemsRuntime.KEY, i1, Optional.of(ItemStatus.PENDING),
                                Optional.of(ItemStatus.CANCELLED)),
                        new StateChange(ItemsRuntime.KEY, i2, Optional.of(ItemStatus.PENDING),
                                Optional.of(ItemStatus.CANCELLED))),
                before.changes());
    }

    @Test
    void theRecorderCountsAReactionOnceEvenThoughTheOutcomeCarriesItToo() {
        Order order = new Order("o1");
        Item i1 = new Item("i1", Optional.of(order));
        Item i2 = new Item("i2", Optional.of(order));
        gateway.accept();
        orders.pay(order);
        items.startPacking(i1);
        items.startPacking(i2);
        items.pack(i1);

        assertThat(items.pack(i2)).isApplied().reactions()
                .forEntity(OrdersRuntime.KEY, order).hasSize(1);

        recorder.assertRecorded().forEntity(OrdersRuntime.KEY, order)
                .hasSize(5)
                .atIndex(4).isApplied().movedTo(OrderStatus.SHIPPED);
    }

    private StateSnapshot track(Order order, Item first, Item second) {
        return StateSnapshot.builder()
                .track(OrdersRuntime.KEY, order, Order::status)
                .track(ItemsRuntime.KEY, first, Item::status)
                .track(ItemsRuntime.KEY, second, Item::status)
                .capture();
    }
}
