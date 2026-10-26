package io.github.graphmaquina.example;

import static io.github.graphmaquina.test.GraphAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import io.github.graphmaquina.api.Eligibility;
import io.github.graphmaquina.example.item.ItemActions;
import io.github.graphmaquina.example.order.Order;
import io.github.graphmaquina.example.order.OrderActions;
import io.github.graphmaquina.example.order.OrderStatus;
import io.github.graphmaquina.example.order.OrderTransition;
import io.github.graphmaquina.example.order.Orders;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Asking the shop whether an operation would be admitted, without making the call. A check reads the order's state and
 * runs the guards of the edge, and answers with the reason a call would be refused for. It writes no state and runs no
 * action and no hook, and the facade's async runs it on the machine's executor.
 */
class EligibilityTest {

    final FakeGateway gateway = new FakeGateway();
    final FakeCarrier carrier = new FakeCarrier();
    final ShopMachines shop = ShopMachinesRuntime.builder(
                    Duration.ofSeconds(10),
                    new OrderActions(gateway, carrier),
                    new ItemActions())
            .build();
    final Orders orders = shop.orders();

    @AfterEach
    void close() {
        shop.close();
    }

    @Test
    void aDeclinedOrderMayRetryWhileItsBudgetLasts() {
        Order order = new Order("o1");
        gateway.decline("51");
        orders.pay(order);

        assertThat(orders.canRetry(order)).isEligible()
                .from(OrderStatus.PAYMENT_DECLINED)
                .transition(OrderTransition.RETRY);
    }

    @Test
    void anExhaustedBudgetExplainsWhy() {
        Order order = new Order("o1");
        gateway.decline("51").decline("51").decline("05");
        orders.pay(order);
        orders.retry(order);
        orders.pay(order);
        orders.retry(order);
        orders.pay(order);

        assertThat(orders.canRetry(order)).isRejectedByGuardNamed("retryBudgetLeft")
                .hasReason("exhausted the 3 charge attempts");
    }

    /** CANCEL leaves NEW and PAYMENT_DECLINED, so a check from PAID finds no edge and gives the state's reason. */
    @Test
    void cancelIsCheckedFromEachOrigin() {
        Order fresh = orderIn(OrderStatus.NEW);
        Order declined = orderIn(OrderStatus.PAYMENT_DECLINED);
        Order paid = orderIn(OrderStatus.PAID);

        assertThat(orders.canCancel(fresh, "c-1")).isEligible().from(OrderStatus.NEW);
        assertThat(orders.canCancel(declined, "c-1")).isEligible().from(OrderStatus.PAYMENT_DECLINED);
        assertThat(orders.canCancel(paid, "c-1")).hasNoTransition()
                .hasReason("money was charged: requires a refund");
    }

    /**
     * PAY charges the gateway and RETRY only moves the order. Checking either one calls no gateway, counts no attempt
     * and leaves the order where it was; the last call shows the gateway does record a charge when one is made.
     */
    @Test
    void aCheckChargesNothing() {
        Order declined = orderIn(OrderStatus.PAYMENT_DECLINED);
        Order fresh = orderIn(OrderStatus.NEW);

        assertThat(orders.canRetry(declined)).isEligible();
        assertThat(orders.canPay(fresh)).isEligible();

        assertEquals(List.of(), gateway.charged);
        assertEquals(0, declined.paymentAttempts());
        assertEquals(0, fresh.paymentAttempts());
        assertThat(declined, Order::status).isIn(OrderStatus.PAYMENT_DECLINED);
        assertThat(fresh, Order::status).isIn(OrderStatus.NEW);

        assertThat(orders.pay(fresh)).isApplied();
        assertEquals(List.of(fresh), gateway.charged);
    }

    /** The facade's async runs the check on the machine's executor, and its future completes with the answer. */
    @Test
    void aCheckRunsOnTheMachinesExecutorThroughAsync() {
        Order order = orderIn(OrderStatus.PAYMENT_DECLINED);
        AtomicReference<Thread> ran = new AtomicReference<>();

        CompletableFuture<Eligibility<Order, OrderStatus, OrderTransition>> pending = orders.async(() -> {
            ran.set(Thread.currentThread());
            return orders.canRetry(order);
        });

        assertThat(pending.join()).isEligible().from(OrderStatus.PAYMENT_DECLINED);
        assertNotSame(Thread.currentThread(), ran.get());
    }

    private static Order orderIn(OrderStatus status) {
        Order order = new Order("o-" + status);
        order.setStatus(status);
        return order;
    }
}
