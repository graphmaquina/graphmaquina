package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.Billing.BOOKINGS;
import static io.github.graphmaquina.fixtures.Billing.PAYMENTS;
import static io.github.graphmaquina.fixtures.Billing.RISKS;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static io.github.graphmaquina.fixtures.Keys.ORDERS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.fixtures.Billing;
import io.github.graphmaquina.fixtures.Billing.BookingStatus;
import io.github.graphmaquina.fixtures.Billing.Payment;
import io.github.graphmaquina.fixtures.Billing.PaymentStatus;
import io.github.graphmaquina.fixtures.Billing.PaymentTransition;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import io.github.graphmaquina.fixtures.Order;
import io.github.graphmaquina.fixtures.OrderStatus;
import io.github.graphmaquina.fixtures.OrderTransition;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * What the registry indexes for sub-machines: for each machine, the bindings in which it is the child, in the order of
 * the builders and then of the parent's states; whether a machine's own definition binds one; and the refusals of a
 * child that is not in the registry or was built from another definition, which consume no builder.
 */
class SubmachineRegistryTest {

    /** An order graph whose NEW state binds {@code payments}; its lookups find nothing. */
    private static GraphDefinition<Order, OrderStatus, OrderTransition> orders(
            GraphDefinition<Payment, PaymentStatus, PaymentTransition> payments) {
        return StateGraph.define(ORDERS)
                .initial(OrderStatus.NEW).terminal(OrderStatus.SHIPPED).terminal(OrderStatus.CANCELLED)
                .allowsCycle(OrderStatus.PAYMENT_DECLINED)
                .statePolicy(OrderStatus.NEW, "an order is charged, declined or cancelled")
                .statePolicy(OrderStatus.PAID, "a paid order ships")
                .statePolicy(OrderStatus.PAYMENT_DECLINED, "a declined order is retried or cancelled")
                .transition(OrderTransition.CHARGE, OrderStatus.NEW, OrderStatus.PAID).endTransition()
                .transition(OrderTransition.DECLINE, OrderStatus.NEW, OrderStatus.PAYMENT_DECLINED).endTransition()
                .transition(OrderTransition.CANCEL, OrderStatus.NEW, OrderStatus.CANCELLED).endTransition()
                .transition(OrderTransition.SHIP, OrderStatus.PAID, OrderStatus.SHIPPED).endTransition()
                .transition(OrderTransition.RETRY, OrderStatus.PAYMENT_DECLINED, OrderStatus.NEW).endTransition()
                .transition(OrderTransition.CANCEL, OrderStatus.PAYMENT_DECLINED, OrderStatus.CANCELLED)
                    .endTransition()
                .submachine(OrderStatus.NEW, payments, (Order order) -> Optional.<Payment>empty(),
                        (Payment payment) -> Optional.<Order>empty())
                    .exit(PaymentStatus.CAPTURED, OrderTransition.CHARGE)
                    .exit(PaymentStatus.DECLINED, OrderTransition.DECLINE)
                    .exit(PaymentStatus.VOIDED, OrderTransition.CANCEL)
                    .endSubmachine()
                .build();
    }

    private static StateMachines.Builder<Order, OrderStatus, OrderTransition> orderMachine(
            GraphDefinition<Payment, PaymentStatus, PaymentTransition> payments) {
        return StateMachines.from(orders(payments)).state(Order::status, Order::setStatus).identity(Order::id);
    }

    /** The parent key and the bound state of each binding, in order. */
    private static List<String> described(List<SubmachineBinding<?, ?, ?, ?, ?, ?>> bindings) {
        return bindings.stream()
                .map((SubmachineBinding<?, ?, ?, ?, ?, ?> binding) ->
                        binding.parentKey() + "." + binding.spec().state())
                .toList();
    }

    @Test
    void eachChildHoldsItsBindingsInTheOrderOfTheBuildersAndThenOfTheParentsStates() {
        Billing billing = new Billing();
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(orderMachine(billing.payments))
                .add(billing.bookingMachine())
                .add(billing.paymentMachine())
                .build()) {
            List<SubmachineBinding<?, ?, ?, ?, ?, ?>> bindings = registry.machineFor(PAYMENTS).bindingsAsChild();
            assertEquals(List.of("Order/OrderStatus.NEW", "Booking/BookingStatus.DEPOSIT",
                    "Booking/BookingStatus.BALANCE"), described(bindings));
            assertSame(ORDERS, bindings.get(0).parentKey());
            assertSame(billing.bookings.submachine(BookingStatus.DEPOSIT).orElseThrow(), bindings.get(1).spec());
            assertSame(billing.bookings.submachine(BookingStatus.BALANCE).orElseThrow(), bindings.get(2).spec());
            assertThrows(UnsupportedOperationException.class, () -> bindings.remove(0));
            assertFalse(registry.machineFor(PAYMENTS).bindsSubmachines());
            assertEquals(List.of(), registry.machineFor(BOOKINGS).bindingsAsChild());
            assertTrue(registry.machineFor(BOOKINGS).bindsSubmachines());
            assertEquals(List.of(), registry.machineFor(ORDERS).bindingsAsChild());
            assertTrue(registry.machineFor(ORDERS).bindsSubmachines());
        }
    }

    /** The risk check governs another state of the payment: the payment machine is its parent and binds it. */
    @Test
    void aChildOverItsParentsOwnEntityIsIndexedLikeAnyOther() {
        Billing billing = Billing.withRiskCheck();
        try (MachineRegistry registry = billing.registry()) {
            assertEquals(List.of("Payment/PaymentStatus.AUTHORIZING"),
                    described(registry.machineFor(RISKS).bindingsAsChild()));
            assertTrue(registry.machineFor(PAYMENTS).bindsSubmachines());
            assertFalse(registry.machineFor(RISKS).bindsSubmachines());
            assertEquals(List.of("Booking/BookingStatus.DEPOSIT", "Booking/BookingStatus.BALANCE"),
                    described(registry.machineFor(PAYMENTS).bindingsAsChild()));
        }
    }

    @Test
    void aMachineWithoutBindingsHoldsNone() {
        GraphDefinition<Doc, DocStatus, DocTransition> docs = StateGraph.define(DOCS)
                .initial(DocStatus.DRAFT).terminal(DocStatus.ARCHIVED)
                .statePolicy(DocStatus.DRAFT, "x").statePolicy(DocStatus.REVIEW, "x")
                .statePolicy(DocStatus.PUBLISHED, "x")
                .transition(DocTransition.SUBMIT, DocStatus.DRAFT, DocStatus.REVIEW).endTransition()
                .transition(DocTransition.PUBLISH, DocStatus.REVIEW, DocStatus.PUBLISHED).endTransition()
                .transition(DocTransition.ARCHIVE, DocStatus.PUBLISHED, DocStatus.ARCHIVED).endTransition()
                .build();
        DefaultStateMachine<Doc, DocStatus, DocTransition> machine =
                (DefaultStateMachine<Doc, DocStatus, DocTransition>) StateMachines.from(docs)
                        .state(Doc::status, Doc::setStatus).build();
        assertEquals(List.of(), machine.bindingsAsChild());
        assertFalse(machine.bindsSubmachines());
    }

    /** The order machine binds payments, which the first build lacks; adding them is enough for the retry. */
    @Test
    void aChildMissingFromTheRegistryIsRefusedAndTheBuildConsumesNothing() {
        Billing billing = new Billing();
        MachineRegistry.Builder builder = MachineRegistry.builder().add(orderMachine(billing.payments));
        IllegalStateException missing = assertThrows(IllegalStateException.class, builder::build);
        assertEquals("the sub-machine of Order/OrderStatus.NEW is Payment/PaymentStatus, which is not in the registry",
                missing.getMessage());
        builder.add(billing.paymentMachine());
        try (MachineRegistry registry = builder.build()) {
            assertEquals(Set.of(ORDERS, PAYMENTS), registry.keys());
        }
    }

    /**
     * The registered payment machine was built from a second definition of the same key: the bookings' binding holds
     * the first. Neither builder is consumed, so the bookings join a registry with the right payments.
     */
    @Test
    void aChildBuiltFromAnotherDefinitionIsRefusedAndTheBuildConsumesNothing() {
        Billing billing = new Billing();
        Billing other = new Billing();
        StateMachines.Builder<Billing.Booking, BookingStatus, Billing.BookingTransition> bookings =
                billing.bookingMachine();
        IllegalStateException foreign = assertThrows(IllegalStateException.class,
                () -> MachineRegistry.builder().add(bookings).add(other.paymentMachine()).build());
        assertEquals("the sub-machine of Booking/BookingStatus.DEPOSIT was built from another definition of"
                + " Payment/PaymentStatus than the registered machine", foreign.getMessage());
        try (MachineRegistry registry = MachineRegistry.builder().add(bookings).add(billing.paymentMachine()).build()) {
            assertEquals(Set.of(BOOKINGS, PAYMENTS), registry.keys());
        }
    }

    /** A standalone machine holds only itself, so a definition that binds another machine is refused there. */
    @Test
    void aStandaloneParentIsRefusedBecauseItsChildIsNotInItsRegistry() {
        Billing billing = new Billing();
        StateMachines.Builder<Order, OrderStatus, OrderTransition> orders = orderMachine(billing.payments);
        IllegalStateException missing = assertThrows(IllegalStateException.class, orders::build);
        assertEquals("the sub-machine of Order/OrderStatus.NEW is Payment/PaymentStatus, which is not in the registry",
                missing.getMessage());
        try (MachineRegistry registry = MachineRegistry.builder().add(orders).add(billing.paymentMachine()).build()) {
            MachineKey<Order, OrderStatus, OrderTransition> key = registry.of(ORDERS).key();
            assertSame(ORDERS, key);
        }
    }
}
