package consumer;

import static consumer.GraphAssertionErrors.assertFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.SubmachineStart;
import io.github.graphmaquina.test.GraphAssertionError;
import io.github.graphmaquina.test.OutcomeAssert;
import io.github.graphmaquina.test.StartAssert;
import io.github.graphmaquina.test.StartsAssert;
import io.github.graphmaquina.test.TypedStartsAssert;
import io.github.graphmaquina.test.fixtures.Checkout;
import io.github.graphmaquina.test.fixtures.Checkout.Order;
import io.github.graphmaquina.test.fixtures.Checkout.OrderChange;
import io.github.graphmaquina.test.fixtures.Checkout.OrderState;
import io.github.graphmaquina.test.fixtures.Checkout.Payment;
import io.github.graphmaquina.test.fixtures.Checkout.PaymentChange;
import io.github.graphmaquina.test.fixtures.Checkout.PaymentState;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Contract of the sub-machine start views, over lists built by hand. Selection is pinned to the child's identity
 * rather than its equality, order and multiplicity are pinned, since a child may start more than once in one call,
 * each mismatch is read off the fields of the {@link GraphAssertionError}, and every expectation whose subject can be
 * null gets its own null-subject case. A null element is refused when the view is built.
 */
class StartAssertionsTest {

    /** A child whose instances are equal when their ids are, to pin selection to identity. */
    record Twin(String id) { }

    private static final MachineKey<Twin, PaymentState, PaymentChange> TWINS =
            MachineKey.of(Twin.class, PaymentState.class, PaymentChange.class);
    private static final String FOR_PAYMENTS = "starts for " + Checkout.PAYMENTS;

    private final Payment payment = new Payment("p1", PaymentState.DECLINED);
    private final Order order = new Order("o1", payment);

    // --- the premise of this view: identity, not equality, selects a child --------------------------

    @Test
    void selectionKeepsTheChildsIdentity() {
        Twin child = new Twin("same-id");
        Twin twin = new Twin("same-id");
        assertEquals(child, twin);
        assertNotSame(child, twin);
        List<SubmachineStart<?, ?, ?>> starts = List.of(new SubmachineStart<>(TWINS, child, PaymentState.DECLINED,
                PaymentState.AUTHORIZING, Checkout.ORDERS, order, OrderState.PAYING, 0));
        StartsAssert.assertThat(starts).forChild(TWINS, child).single().startedAt(PaymentState.AUTHORIZING);
        StartsAssert.assertThat(starts).forChild(TWINS, twin).hasSize(0);
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> StartsAssert.assertThat(starts).forChild(TWINS, twin).single()),
                "starts for " + TWINS, "size", 1, 0);
    }

    // --- the untyped list --------------------------------------------------------------------------

    @Test
    void sizeIsReadOffTheWholeList() {
        StartsAssert view = StartsAssert.assertThat(List.of(start(OrderState.PAYING, 0), start(OrderState.PAYING, 1)));
        assertSame(view, view.hasSize(2));
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasSize(1)), "starts", "size", 1, 2);
    }

    @Test
    void anEmptyListSatisfiesOnlyIsEmpty() {
        StartsAssert empty = StartsAssert.assertThat(List.of());
        assertSame(empty, empty.isEmpty());
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> StartsAssert.assertThat(List.of(start(OrderState.PAYING, 0))).isEmpty()),
                "starts", "size", 0, 1);
    }

    @Test
    void theViewCopiesTheListItWasGiven() {
        List<SubmachineStart<?, ?, ?>> mutable = new ArrayList<>();
        mutable.add(start(OrderState.PAYING, 0));
        StartsAssert view = StartsAssert.assertThat(mutable);
        mutable.add(start(OrderState.PAYING, 1));
        mutable.clear();
        view.hasSize(1);
        view.forChild(Checkout.PAYMENTS, payment).hasSize(1);
    }

    @Test
    void aNullElementIsRefusedWhenTheViewIsBuilt() {
        List<SubmachineStart<?, ?, ?>> withNull = Arrays.asList(start(OrderState.PAYING, 0), null);
        assertThrows(NullPointerException.class, () -> StartsAssert.assertThat(withNull));
    }

    // --- selection ---------------------------------------------------------------------------------

    @Test
    void selectionSkipsStartsOfAnotherMachineOrAnotherChild() {
        Payment other = new Payment("p2", PaymentState.AUTHORIZING);
        Twin twin = new Twin("p1");
        StartsAssert view = StartsAssert.assertThat(List.of(
                new SubmachineStart<>(TWINS, twin, PaymentState.DECLINED, PaymentState.AUTHORIZING,
                        Checkout.ORDERS, order, OrderState.PAYING, 0),
                start(OrderState.PAYING, 0),
                new SubmachineStart<>(Checkout.PAYMENTS, other, PaymentState.AUTHORIZING, PaymentState.AUTHORIZING,
                        Checkout.ORDERS, order, OrderState.PAYING, 0)));
        view.hasSize(3);
        view.forChild(Checkout.PAYMENTS, payment).single().hadPrevious(PaymentState.DECLINED);
        view.forChild(Checkout.PAYMENTS, other).single().hadPrevious(PaymentState.AUTHORIZING);
        view.forChild(TWINS, twin).single().hadPrevious(PaymentState.DECLINED);
    }

    @Test
    void aKeyThatEqualsTheStartsKeyMatchesEvenAsAFreshInstance() {
        MachineKey<Payment, PaymentState, PaymentChange> sameValue =
                MachineKey.of(Payment.class, PaymentState.class, PaymentChange.class);
        assertEquals(Checkout.PAYMENTS, sameValue);
        assertNotSame(Checkout.PAYMENTS, sameValue);
        StartsAssert.assertThat(List.of(start(OrderState.PAYING, 0))).forChild(sameValue, payment).single()
                .byParent(MachineKey.of(Order.class, OrderState.class, OrderChange.class), order, OrderState.PAYING);
    }

    /** A child may start more than once in one call, so its starts keep their order and are read by index. */
    @Test
    void aChildThatStartedTwiceKeepsBothStartsInOrder() {
        TypedStartsAssert<Payment, PaymentState, PaymentChange> twice = StartsAssert.assertThat(
                        List.of(start(OrderState.PAYING, 0), start(OrderState.PAYING, 2)))
                .forChild(Checkout.PAYMENTS, payment);
        assertSame(twice, twice.hasSize(2));
        twice.atIndex(0).atDepth(0);
        twice.atIndex(1).atDepth(2);
        assertFailure(assertThrows(GraphAssertionError.class, () -> twice.hasSize(1)), FOR_PAYMENTS, "size", 1, 2);
        assertFailure(assertThrows(GraphAssertionError.class, twice::single), FOR_PAYMENTS, "size", 1, 2);
        assertFailure(assertThrows(GraphAssertionError.class, () -> twice.atIndex(1).atDepth(0)),
                FOR_PAYMENTS + "[1]", "depth", 0, 2);
    }

    @Test
    void aMissingIndexIsAnAssertionFailureAndANegativeOneAnArgumentError() {
        TypedStartsAssert<Payment, PaymentState, PaymentChange> once =
                StartsAssert.assertThat(List.of(start(OrderState.PAYING, 0))).forChild(Checkout.PAYMENTS, payment);
        assertFailure(assertThrows(GraphAssertionError.class, () -> once.atIndex(1)),
                FOR_PAYMENTS, "index 1 present", Boolean.TRUE, Boolean.FALSE);
        assertEquals("index must not be negative: -1",
                assertThrows(IllegalArgumentException.class, () -> once.atIndex(-1)).getMessage());
    }

    // --- one start ---------------------------------------------------------------------------------

    @Test
    void everyFieldOfAStartIsItsOwnExpectation() {
        StartAssert<Payment, PaymentState, PaymentChange> view = single(start(OrderState.PAYING, 1));
        assertSame(view, view.startedAt(PaymentState.AUTHORIZING));
        assertSame(view, view.hadPrevious(PaymentState.DECLINED));
        assertSame(view, view.byParent(Checkout.ORDERS, order, OrderState.PAYING));
        assertSame(view, view.atDepth(1));
        String context = FOR_PAYMENTS + "[0]";
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.startedAt(PaymentState.DECLINED)),
                context, "initial", PaymentState.DECLINED, PaymentState.AUTHORIZING);
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hadPrevious(PaymentState.AUTHORIZING)),
                context, "previous", PaymentState.AUTHORIZING, PaymentState.DECLINED);
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.byParent(Checkout.ORDERS, order,
                        OrderState.CART)),
                context, "node", OrderState.CART, OrderState.PAYING);
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.atDepth(0)), context, "depth", 0, 1);
    }

    /** The parent is compared by key, then by reference, then by the state that holds the binding. */
    @Test
    void theParentIsComparedByKeyAndByReference() {
        StartAssert<Payment, PaymentState, PaymentChange> view = single(start(OrderState.PAYING, 0));
        String context = FOR_PAYMENTS + "[0]";
        MachineKey<Order, PaymentState, PaymentChange> otherKey =
                MachineKey.of(Order.class, PaymentState.class, PaymentChange.class);
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> view.byParent(otherKey, order, PaymentState.AUTHORIZING)),
                context, "parent key", otherKey, Checkout.ORDERS);
        Order other = new Order("o1", new Payment("p1", PaymentState.DECLINED));
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> view.byParent(Checkout.ORDERS, other, OrderState.PAYING)),
                context, "parent", other, order);
    }

    // --- arguments ---------------------------------------------------------------------------------

    @Test
    void aNullRequiredArgumentIsAnArgumentErrorNotAnAssertionFailure() {
        StartsAssert starts = StartsAssert.assertThat(List.of(start(OrderState.PAYING, 0)));
        assertEquals("key", assertThrows(NullPointerException.class,
                () -> starts.forChild(null, payment)).getMessage());
        assertEquals("child", assertThrows(NullPointerException.class,
                () -> starts.forChild(Checkout.PAYMENTS, null)).getMessage());
        StartAssert<Payment, PaymentState, PaymentChange> view = single(start(OrderState.PAYING, 0));
        assertEquals("expected", assertThrows(NullPointerException.class,
                () -> view.startedAt(null)).getMessage());
        assertEquals("expected", assertThrows(NullPointerException.class,
                () -> view.hadPrevious(null)).getMessage());
        assertEquals("key", assertThrows(NullPointerException.class,
                () -> view.byParent(null, order, OrderState.PAYING)).getMessage());
        assertEquals("parent", assertThrows(NullPointerException.class,
                () -> view.byParent(Checkout.ORDERS, null, OrderState.PAYING)).getMessage());
        assertEquals("node", assertThrows(NullPointerException.class,
                () -> view.byParent(Checkout.ORDERS, order, null)).getMessage());
    }

    // --- one null-subject case per expectation method -----------------------------------------------

    @Test
    void nullSubjectFailsHasSize() {
        assertFailure(assertThrows(GraphAssertionError.class, () -> StartsAssert.assertThat(null).hasSize(0)),
                "starts", "size", 0, null);
    }

    @Test
    void nullSubjectFailsIsEmpty() {
        assertFailure(assertThrows(GraphAssertionError.class, () -> StartsAssert.assertThat(null).isEmpty()),
                "starts", "size", 0, null);
    }

    @Test
    void nullSubjectFailsForChild() {
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> StartsAssert.assertThat(null).forChild(Checkout.PAYMENTS, payment)),
                "starts", "child starts", payment, null);
    }

    @Test
    void nullOutcomeFailsStarts() {
        Outcome<Order, OrderState, OrderChange> none = null;
        assertFailure(assertThrows(GraphAssertionError.class, () -> OutcomeAssert.assertThat(none).starts().hasSize(0)),
                "outcome.starts", "size", 0, null);
    }

    // --- helpers -----------------------------------------------------------------------------------

    /** The payment, started from DECLINED at AUTHORIZING by the order entering {@code node}, at {@code depth}. */
    private SubmachineStart<Payment, PaymentState, PaymentChange> start(OrderState node, int depth) {
        return new SubmachineStart<>(Checkout.PAYMENTS, payment, PaymentState.DECLINED, PaymentState.AUTHORIZING,
                Checkout.ORDERS, order, node, depth);
    }

    private StartAssert<Payment, PaymentState, PaymentChange> single(SubmachineStart<?, ?, ?> start) {
        return StartsAssert.assertThat(List.of(start)).forChild(Checkout.PAYMENTS, payment).single();
    }
}
