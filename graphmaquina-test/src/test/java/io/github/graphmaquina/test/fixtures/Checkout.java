package io.github.graphmaquina.test.fixtures;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.runtime.MachineRegistry;
import io.github.graphmaquina.runtime.StateMachines;
import java.time.InstantSource;
import java.util.Optional;
import java.util.function.Consumer;
import lombok.Getter;

/**
 * Sub-machine fixture for this module's own tests, built with the DSL and run by a real registry: an order whose
 * {@code PAYING} state holds its payment. Checking out enters {@code PAYING}, which starts the payment at
 * {@code AUTHORIZING}; the payment's terminal states {@code CAPTURED} and {@code DECLINED} exit through {@code PAY}
 * and {@code REFUSE}, and {@code ABANDON} leaves {@code CART} or {@code PAYING} for {@code ABANDONED}. The edge
 * {@code CHECKOUT} runs the after-commit hook {@code audit}, which runs {@link #audit}, so a test makes that hook
 * throw by replacing the field.
 */
public final class Checkout {

    /** States of an order. */
    public enum OrderState { CART, PAYING, PAID, REFUSED, ABANDONED }

    /** Identifiers of the order machine; {@code PAY} and {@code REFUSE} are the exits of the payment. */
    public enum OrderChange { CHECKOUT, PAY, REFUSE, ABANDON }

    /** States of a payment: two terminal outcomes of one authorization. */
    public enum PaymentState { AUTHORIZING, CAPTURED, DECLINED }

    /** Identifiers of the payment machine. */
    public enum PaymentChange { CAPTURE, DECLINE }

    /** A payment, in the state it is created with, which knows its order once the order is created. */
    @Getter
    public static final class Payment {

        private final String id;
        private PaymentState state;
        private Optional<Order> order = Optional.empty();

        public Payment(String id, PaymentState state) {
            this.id = id;
            this.state = state;
        }

        public void setState(PaymentState state) { this.state = state; }

        @Override
        public String toString() { return "Payment#" + id; }
    }

    /** An order in {@code CART}, paid by its payment while the order is {@code PAYING}. */
    @Getter
    public static final class Order {

        private final String id;
        private final Payment payment;
        private OrderState state = OrderState.CART;

        public Order(String id, Payment payment) {
            this.id = id;
            this.payment = payment;
            payment.order = Optional.of(this);
        }

        public void setState(OrderState state) { this.state = state; }

        @Override
        public String toString() { return "Order#" + id; }
    }

    public static final MachineKey<Order, OrderState, OrderChange> ORDERS =
            MachineKey.of(Order.class, OrderState.class, OrderChange.class);
    public static final MachineKey<Payment, PaymentState, PaymentChange> PAYMENTS =
            MachineKey.of(Payment.class, PaymentState.class, PaymentChange.class);

    /** Runs as the after-commit hook {@code audit} of {@code CHECKOUT}; replace it to make that hook throw. */
    public Consumer<Order> audit = (Order order) -> { };

    public final GraphDefinition<Payment, PaymentState, PaymentChange> payments = StateGraph.define(PAYMENTS)
            .initial(PaymentState.AUTHORIZING)
            .terminal(PaymentState.CAPTURED).terminal(PaymentState.DECLINED)
            .statePolicy(PaymentState.AUTHORIZING, "capture or decline the authorization")
            .transition(PaymentChange.CAPTURE, PaymentState.AUTHORIZING, PaymentState.CAPTURED).endTransition()
            .transition(PaymentChange.DECLINE, PaymentState.AUTHORIZING, PaymentState.DECLINED).endTransition()
            .build();

    public final GraphDefinition<Order, OrderState, OrderChange> orders = StateGraph.define(ORDERS)
            .initial(OrderState.CART)
            .terminal(OrderState.PAID).terminal(OrderState.REFUSED).terminal(OrderState.ABANDONED)
            .statePolicy(OrderState.CART, "check the cart out first")
            .statePolicy(OrderState.PAYING, "the payment decides")
            .transition(OrderChange.CHECKOUT, OrderState.CART, OrderState.PAYING)
                .afterCommit("audit", (Order order) -> audit.accept(order)).endTransition()
            .transition(OrderChange.PAY, OrderState.PAYING, OrderState.PAID).endTransition()
            .transition(OrderChange.REFUSE, OrderState.PAYING, OrderState.REFUSED).endTransition()
            .transition(OrderChange.ABANDON, OrderState.CART, OrderState.ABANDONED).endTransition()
            .transition(OrderChange.ABANDON, OrderState.PAYING, OrderState.ABANDONED).endTransition()
            .submachine(OrderState.PAYING, payments, (Order order) -> Optional.of(order.payment()), Payment::order)
                .exit(PaymentState.CAPTURED, OrderChange.PAY)
                .exit(PaymentState.DECLINED, OrderChange.REFUSE)
                .endSubmachine()
            .build();

    /** The order and payment machines, each reading and writing its entity's state and identified by its id. */
    public MachineRegistry registry() {
        return MachineRegistry.builder()
                .add(StateMachines.from(orders).state(Order::state, Order::setState).identity(Order::id))
                .add(StateMachines.from(payments).state(Payment::state, Payment::setState).identity(Payment::id))
                .build();
    }

    /** The same machines, the order machine recording the trail of every call it starts on {@code clock}. */
    public MachineRegistry registry(InstantSource clock) {
        return MachineRegistry.builder()
                .add(StateMachines.from(orders).state(Order::state, Order::setState).identity(Order::id)
                        .recordTrail(clock))
                .add(StateMachines.from(payments).state(Payment::state, Payment::setState).identity(Payment::id))
                .build();
    }
}
