package consumer;

import static io.github.graphmaquina.test.GraphAssertions.assertThat;
import static io.github.graphmaquina.test.GraphAssertions.captureFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.RejectionKind;
import io.github.graphmaquina.api.StatesWritten;
import io.github.graphmaquina.runtime.MachineRegistry;
import io.github.graphmaquina.test.GraphAssertionError;
import io.github.graphmaquina.test.RejectedOutcomeAssert;
import io.github.graphmaquina.test.fixtures.Checkout;
import io.github.graphmaquina.test.fixtures.Checkout.Order;
import io.github.graphmaquina.test.fixtures.Checkout.OrderChange;
import io.github.graphmaquina.test.fixtures.Checkout.OrderState;
import io.github.graphmaquina.test.fixtures.Checkout.Payment;
import io.github.graphmaquina.test.fixtures.Checkout.PaymentChange;
import io.github.graphmaquina.test.fixtures.Checkout.PaymentState;
import org.junit.jupiter.api.Test;

/**
 * The sub-machine expectations of the outcome and failure views, over calls a real registry ran on the checkout
 * fixture: the start an outcome carries when its call enters the bound state, the starts an after-commit failure
 * carries, and the two refusals of a sub-machine, each told apart from the other kinds by the field {@code kind}.
 */
class SubmachineOutcomeAssertionsTest {

    private final Checkout checkout = new Checkout();
    private final Payment payment = new Payment("p1", PaymentState.DECLINED);
    private final Order order = new Order("o1", payment);

    @Test
    void aCallThatEntersTheBoundStateCarriesTheStartOfTheChild() {
        try (MachineRegistry registry = checkout.registry()) {
            assertThat(registry.of(Checkout.ORDERS).execute(order, OrderChange.CHECKOUT)).isApplied()
                    .movedTo(OrderState.PAYING)
                    .starts().hasSize(1)
                    .forChild(Checkout.PAYMENTS, payment).single()
                    .startedAt(PaymentState.AUTHORIZING)
                    .hadPrevious(PaymentState.DECLINED)
                    .byParent(Checkout.ORDERS, order, OrderState.PAYING)
                    .atDepth(0);
            assertEquals(PaymentState.AUTHORIZING, payment.state());
        }
    }

    /** The exit is a hop of the child's call, not a start: the call that ends the child starts nothing. */
    @Test
    void aCallThatEndsTheChildCarriesItsExitAsAHopAndNoStart() {
        try (MachineRegistry registry = checkout.registry()) {
            registry.of(Checkout.ORDERS).execute(order, OrderChange.CHECKOUT);
            Outcome<Payment, PaymentState, PaymentChange> captured =
                    registry.of(Checkout.PAYMENTS).execute(payment, PaymentChange.CAPTURE);
            assertThat(captured).isApplied().movedTo(PaymentState.CAPTURED).starts().isEmpty();
            assertThat(captured).records().forEntity(Checkout.ORDERS, order).single()
                    .from(OrderState.PAYING).hasTransition(OrderChange.PAY).to(OrderState.PAID).hasDepth(1);
            assertFailure(assertThrows(GraphAssertionError.class, () -> assertThat(captured).starts().hasSize(1)),
                    "outcome.starts", "size", 1, 0);
        }
    }

    /** A hook that throws after the commit leaves every state written, and the failure keeps the starts of the call. */
    @Test
    void anAfterCommitFailureCarriesTheStartsOfItsCall() {
        IllegalStateException down = new IllegalStateException("audit down");
        checkout.audit = (Order audited) -> {
            throw down;
        };
        try (MachineRegistry registry = checkout.registry()) {
            assertThat(captureFailure(() -> registry.of(Checkout.ORDERS).execute(order, OrderChange.CHECKOUT)))
                    .isAfterCommit().hasHookNamed("audit").hasCauseSameAs(down)
                    .hasPhase(CallPhase.HOOK).hasStatesWritten(StatesWritten.ALL)
                    .starts().hasSize(1)
                    .forChild(Checkout.PAYMENTS, payment).single()
                    .startedAt(PaymentState.AUTHORIZING)
                    .byParent(Checkout.ORDERS, order, OrderState.PAYING);
            assertFailure(assertThrows(GraphAssertionError.class, () -> assertThat(captureFailure(
                            () -> registry.of(Checkout.ORDERS).execute(new Order("o2",
                                    new Payment("p2", PaymentState.AUTHORIZING)), OrderChange.CHECKOUT)))
                            .isAfterCommit().starts().isEmpty()),
                    "after-commit failure.starts", "size", 0, 1);
        }
    }

    @Test
    void aChildWhoseParentLeftTheBoundStateIsDetached() {
        try (MachineRegistry registry = checkout.registry()) {
            registry.of(Checkout.ORDERS).execute(order, OrderChange.CHECKOUT);
            registry.of(Checkout.ORDERS).execute(order, OrderChange.ABANDON);
            RejectedOutcomeAssert<Payment, PaymentState, PaymentChange> view =
                    assertThat(registry.of(Checkout.PAYMENTS).execute(payment, PaymentChange.CAPTURE)).isRejected();
            assertSame(view, view.isDetached());
            view.hasReason("no parent holds it in PAYING");
            assertFailure(assertThrows(GraphAssertionError.class, view::isReservedExit),
                    "rejected outcome", "kind", RejectionKind.RESERVED_EXIT, RejectionKind.DETACHED);
            assertFailure(assertThrows(GraphAssertionError.class, view::hasNoTransition),
                    "rejected outcome", "kind", RejectionKind.NO_TRANSITION, RejectionKind.DETACHED);
            assertEquals(PaymentState.AUTHORIZING, payment.state());
        }
    }

    @Test
    void anExitRequestedOnTheParentIsAReservedExit() {
        try (MachineRegistry registry = checkout.registry()) {
            registry.of(Checkout.ORDERS).execute(order, OrderChange.CHECKOUT);
            RejectedOutcomeAssert<Order, OrderState, OrderChange> view =
                    assertThat(registry.of(Checkout.ORDERS).execute(order, OrderChange.PAY)).isRejected();
            assertSame(view, view.isReservedExit());
            view.hasReason("PAY leaves PAYING only when its sub-machine ends");
            assertFailure(assertThrows(GraphAssertionError.class, view::isDetached),
                    "rejected outcome", "kind", RejectionKind.DETACHED, RejectionKind.RESERVED_EXIT);
            assertEquals(OrderState.PAYING, order.state());
        }
    }

    @Test
    void aRequestWithNoEdgeIsNeitherDetachedNorAReservedExit() {
        try (MachineRegistry registry = checkout.registry()) {
            RejectedOutcomeAssert<Order, OrderState, OrderChange> view =
                    assertThat(registry.of(Checkout.ORDERS).execute(order, OrderChange.PAY)).isRejected();
            view.hasNoTransition();
            assertFailure(assertThrows(GraphAssertionError.class, view::isDetached),
                    "rejected outcome", "kind", RejectionKind.DETACHED, RejectionKind.NO_TRANSITION);
            assertFailure(assertThrows(GraphAssertionError.class, view::isReservedExit),
                    "rejected outcome", "kind", RejectionKind.RESERVED_EXIT, RejectionKind.NO_TRANSITION);
        }
    }

    private static void assertFailure(GraphAssertionError error, String context, String property,
            Object expected, Object actual) {
        assertEquals(context, error.context());
        assertEquals(property, error.property());
        assertEquals(expected, error.expected());
        assertEquals(actual, error.actual());
    }
}
