package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.Billing.BOOKINGS;
import static io.github.graphmaquina.fixtures.Billing.PAYMENTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.OutcomeCode;
import io.github.graphmaquina.api.RejectionKind;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.StatesWritten;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.TransitionRecord;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.error.AmbiguousIdentityException;
import io.github.graphmaquina.error.AmbiguousParentException;
import io.github.graphmaquina.error.ReentrantTransitionException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.error.UninitializedStateException;
import io.github.graphmaquina.fixtures.Billing;
import io.github.graphmaquina.fixtures.Billing.Booking;
import io.github.graphmaquina.fixtures.Billing.BookingStatus;
import io.github.graphmaquina.fixtures.Billing.BookingTransition;
import io.github.graphmaquina.fixtures.Billing.Payment;
import io.github.graphmaquina.fixtures.Billing.PaymentStatus;
import io.github.graphmaquina.fixtures.Billing.PaymentTransition;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

/**
 * The binding check that runs before a child's edge is looked up, and the exits reserved to a binding. A child whose
 * parent is in the bound node, or leaving it, steps as usual; one whose parent is elsewhere is rejected as DETACHED,
 * and one with no parent is a free machine. Two active parents, a lookup that breaks its contract or throws, and a
 * parent that cannot be read abort the call: in RESOLVE at the root, in ACTION and poisoning inside a cascade. An
 * identifier that a binding maps as an exit is refused from the bound node as RESERVED_EXIT.
 */
class SubmachineBindingTest {

    /** States of a ledger: it settles a payment or holds a booking, then closes or fails. */
    enum LedgerStatus { OPEN, SETTLING, HOLDING, CLOSED, FAILED }

    /** Identifiers of the ledger machine. */
    enum LedgerTransition { SETTLE, HOLD, CLOSE, FAIL }

    static final MachineKey<Ledger, LedgerStatus, LedgerTransition> LEDGERS =
            MachineKey.of(Ledger.class, LedgerStatus.class, LedgerTransition.class);

    /** A second parent of payments, which also holds bookings as its own children. */
    static final class Ledger {

        private final String id;
        private LedgerStatus status;
        private Optional<Payment> settlement = Optional.empty();
        private Optional<Booking> held = Optional.empty();

        Ledger(String id, LedgerStatus status) {
            this.id = id;
            this.status = status;
        }

        String id() { return id; }

        LedgerStatus status() { return status; }

        void setStatus(LedgerStatus status) { this.status = status; }

        @Override
        public String toString() { return "Ledger#" + id; }
    }

    private final Billing billing = new Billing();
    private final List<Ledger> ledgers = new ArrayList<>();

    /** Payments settle on {@code SETTLING} and bookings are held on {@code HOLDING}; the lookups search the ledgers. */
    private GraphDefinition<Ledger, LedgerStatus, LedgerTransition> ledgerGraph() {
        return StateGraph.define(LEDGERS)
                .initial(LedgerStatus.OPEN).terminal(LedgerStatus.CLOSED).terminal(LedgerStatus.FAILED)
                .statePolicy(LedgerStatus.OPEN, "a ledger settles a payment or holds a booking")
                .statePolicy(LedgerStatus.SETTLING, "a settlement closes or fails")
                .statePolicy(LedgerStatus.HOLDING, "a hold closes or fails")
                .transition(LedgerTransition.SETTLE, LedgerStatus.OPEN, LedgerStatus.SETTLING).endTransition()
                .transition(LedgerTransition.HOLD, LedgerStatus.OPEN, LedgerStatus.HOLDING).endTransition()
                .transition(LedgerTransition.CLOSE, LedgerStatus.SETTLING, LedgerStatus.CLOSED).endTransition()
                .transition(LedgerTransition.FAIL, LedgerStatus.SETTLING, LedgerStatus.FAILED).endTransition()
                .transition(LedgerTransition.CLOSE, LedgerStatus.HOLDING, LedgerStatus.CLOSED).endTransition()
                .transition(LedgerTransition.FAIL, LedgerStatus.HOLDING, LedgerStatus.FAILED).endTransition()
                .submachine(LedgerStatus.SETTLING, billing.payments, (Ledger ledger) -> ledger.settlement,
                        (Payment payment) -> ledgers.stream()
                                .filter((Ledger ledger) -> ledger.settlement.equals(Optional.of(payment))).findFirst())
                    .exit(PaymentStatus.CAPTURED, LedgerTransition.CLOSE)
                    .exit(PaymentStatus.DECLINED, LedgerTransition.FAIL)
                    .exit(PaymentStatus.VOIDED, LedgerTransition.FAIL)
                    .endSubmachine()
                .submachine(LedgerStatus.HOLDING, billing.bookings, (Ledger ledger) -> ledger.held,
                        (Booking booking) -> ledgers.stream()
                                .filter((Ledger ledger) -> ledger.held.equals(Optional.of(booking))).findFirst())
                    .exit(BookingStatus.CONFIRMED, LedgerTransition.CLOSE)
                    .exit(BookingStatus.CANCELLED, LedgerTransition.FAIL)
                    .endSubmachine()
                .build();
    }

    /** The billing machines plus the ledger machine, built after them. */
    private MachineRegistry registryWithLedgers() {
        return MachineRegistry.builder().add(billing.bookingMachine()).add(billing.paymentMachine())
                .add(StateMachines.from(ledgerGraph()).state(Ledger::status, Ledger::setStatus).identity(Ledger::id))
                .build();
    }

    private static <T> T single(List<T> values) {
        assertEquals(1, values.size(), values.toString());
        return values.get(0);
    }

    private static List<String> described(List<TransitionRecord<?, ?, ?>> records) {
        return records.stream().map((TransitionRecord<?, ?, ?> record) -> record.entity() + ":" + record.from() + "-"
                + record.transition() + ">" + record.to() + "@" + record.depth()).toList();
    }

    @Test
    void aChildWhoseParentIsInTheNodeSteps() {
        Payment deposit = new Payment("d1", PaymentStatus.AUTHORIZING);
        Booking booking = new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(deposit);
        try (MachineRegistry registry = billing.registry()) {
            Outcome<Payment, PaymentStatus, PaymentTransition> outcome =
                    registry.of(PAYMENTS).execute(deposit, PaymentTransition.HOLD);
            assertEquals(OutcomeCode.APPLIED, outcome.code());
            assertEquals(List.of("Payment#d1:AUTHORIZING-HOLD>HELD@0"), described(outcome.records()));
            assertEquals(PaymentStatus.HELD, deposit.status());
            assertEquals(BookingStatus.DEPOSIT, booking.status());
        }
    }

    /** One payment is both deposit and balance: the binding of the node the booking is in holds it. */
    @Test
    void theBindingOfTheNodeTheParentIsInIsTheActiveOne() {
        Payment payment = new Payment("p1", PaymentStatus.AUTHORIZING);
        new Booking("b1", BookingStatus.BALANCE).attachDeposit(payment).attachBalance(payment);
        try (MachineRegistry registry = billing.registry()) {
            Outcome<Payment, PaymentStatus, PaymentTransition> outcome =
                    registry.of(PAYMENTS).execute(payment, PaymentTransition.HOLD);
            assertEquals(OutcomeCode.APPLIED, outcome.code());
            assertEquals(PaymentStatus.HELD, payment.status());
        }
    }

    /** No binding finds a parent: the child is a free machine. */
    @Test
    void aFreeChildStepsAsAnyMachine() {
        Payment payment = new Payment("p1", PaymentStatus.AUTHORIZING);
        try (MachineRegistry registry = billing.registry()) {
            Outcome<Payment, PaymentStatus, PaymentTransition> outcome =
                    registry.of(PAYMENTS).execute(payment, PaymentTransition.CAPTURE);
            assertEquals(OutcomeCode.APPLIED, outcome.code());
            assertEquals(PaymentStatus.CAPTURED, payment.status());
            assertEquals(List.of("Payment#p1:AUTHORIZING-CAPTURE>CAPTURED@0"), billing.paymentActions.calls);
        }
    }

    /**
     * Both bindings find the booking through the payment, and neither holds it: the reason names both nodes, in
     * binding order. Nothing runs and nothing is written.
     */
    @Test
    void aChildWhoseParentIsElsewhereIsRejectedAsDetached() {
        Payment deposit = new Payment("d1", PaymentStatus.AUTHORIZING);
        Booking booking = new Booking("b1", BookingStatus.REQUESTED).attachDeposit(deposit);
        try (MachineRegistry registry = billing.registry()) {
            Outcome<Payment, PaymentStatus, PaymentTransition> outcome =
                    registry.of(PAYMENTS).execute(deposit, PaymentTransition.CAPTURE);
            Outcome.Rejected<Payment, PaymentStatus, PaymentTransition> rejected = outcome.asRejected().orElseThrow();
            assertEquals(RejectionKind.DETACHED, rejected.kind());
            assertEquals(OutcomeCode.DETACHED, outcome.code());
            assertEquals(Optional.of("the parent is not in DEPOSIT or BALANCE"), outcome.explanation());
            assertEquals(Optional.empty(), rejected.guard());
            assertEquals(PaymentStatus.AUTHORIZING, outcome.from());
            assertEquals(List.of(), billing.paymentActions.calls);
            assertEquals(List.of(), billing.hooks);
            assertEquals(PaymentStatus.AUTHORIZING, deposit.status());
            assertEquals(BookingStatus.REQUESTED, booking.status());
        }
    }

    /** The check comes before the edge is looked up: a detached child is not told that the edge is missing. */
    @Test
    void aDetachedChildIsRefusedBeforeItsEdgeIsLookedUp() {
        Payment deposit = new Payment("d1", PaymentStatus.CAPTURED);
        new Booking("b1", BookingStatus.REQUESTED).attachDeposit(deposit);
        try (MachineRegistry registry = billing.registry()) {
            Outcome<Payment, PaymentStatus, PaymentTransition> outcome =
                    registry.of(PAYMENTS).execute(deposit, PaymentTransition.HOLD);
            assertEquals(OutcomeCode.DETACHED, outcome.code());
        }
    }

    /** The booking is in DEPOSIT but its deposit is another payment, so it holds this one under no binding. */
    @Test
    void aParentWhoseChildOfAnswersAnotherChildDoesNotHoldIt() {
        Payment stale = new Payment("d1", PaymentStatus.AUTHORIZING);
        Payment current = new Payment("d2", PaymentStatus.AUTHORIZING);
        new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(stale).attachDeposit(current);
        try (MachineRegistry registry = billing.registry()) {
            Outcome<Payment, PaymentStatus, PaymentTransition> outcome =
                    registry.of(PAYMENTS).execute(stale, PaymentTransition.HOLD);
            assertEquals(OutcomeCode.DETACHED, outcome.code());
            assertEquals(Optional.of("the parent is not in DEPOSIT or BALANCE"), outcome.explanation());
            assertEquals(PaymentStatus.AUTHORIZING, stale.status());
        }
    }

    /** During the action of an edge that leaves the node, the binding still holds: the action may move the child. */
    @Test
    void aParentLeavingTheNodeStillHoldsItsChild() {
        Payment deposit = new Payment("d1", PaymentStatus.AUTHORIZING);
        Booking booking = new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(deposit);
        billing.bookingActions.behaviour = (TransitionContext<Booking, BookingStatus, BookingTransition> ctx) -> {
            ctx.cascade(PAYMENTS, deposit, PaymentTransition.HOLD);
            return ActionOutcome.ok();
        };
        try (MachineRegistry registry = billing.registry()) {
            Outcome<Booking, BookingStatus, BookingTransition> outcome =
                    registry.of(BOOKINGS).execute(booking, BookingTransition.CANCEL);
            assertEquals(OutcomeCode.APPLIED, outcome.code());
            assertEquals(List.of("Booking#b1:DEPOSIT-CANCEL>CANCELLED@0", "Payment#d1:AUTHORIZING-HOLD>HELD@1"),
                    described(outcome.records()));
            assertEquals(BookingStatus.CANCELLED, booking.status());
            assertEquals(PaymentStatus.HELD, deposit.status());
        }
    }

    /**
     * A detached child inside a cascade aborts like any nested rejection, and the abort poisons the transaction: the
     * action that cascaded catches it and returns ok, and the root still throws that very abort.
     */
    @Test
    void aDetachedChildInsideACascadeAbortsTheCall() {
        Payment deposit = new Payment("d1", PaymentStatus.AUTHORIZING);
        Booking owner = new Booking("b1", BookingStatus.REQUESTED).attachDeposit(deposit);
        Booking booking = new Booking("b2", BookingStatus.REQUESTED);
        List<RuntimeException> caught = new ArrayList<>();
        billing.bookingActions.behaviour = (TransitionContext<Booking, BookingStatus, BookingTransition> ctx) -> {
            try {
                ctx.cascade(PAYMENTS, deposit, PaymentTransition.CAPTURE);
            } catch (TransitionAbortedException e) {
                caught.add(e);
            }
            return ActionOutcome.ok();
        };
        try (MachineRegistry registry = billing.registry()) {
            StateMachine<Booking, BookingStatus, BookingTransition> bookings = registry.of(BOOKINGS);
            TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                    () -> bookings.execute(booking, BookingTransition.CANCEL));
            assertSame(single(caught), error);
            assertEquals(CallPhase.ACTION, error.phase());
            assertEquals(StatesWritten.NONE, error.statesWritten());
            assertEquals("transition aborted in Payment/PaymentStatus AUTHORIZING -CAPTURE-> ?: rejected inside a"
                    + " cascade by detached sub-machine: the parent is not in DEPOSIT or BALANCE"
                    + " [route Booking/BookingStatus#b2]", error.getMessage());
            assertEquals(List.of(), billing.paymentActions.calls);
            assertEquals(BookingStatus.REQUESTED, booking.status());
            assertEquals(BookingStatus.REQUESTED, owner.status());
        }
    }

    /**
     * Review Focus #3: a cascade earlier in the call moved the parent out of the node. The check reads the parent's
     * pending state, not its getter, which still says DEPOSIT, so the child is detached and the call aborts.
     */
    @Test
    void aCascadeThatMovedTheParentOutOfTheNodeDetachesTheChild() {
        Payment deposit = new Payment("d1", PaymentStatus.AUTHORIZING);
        Booking owner = new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(deposit);
        Booking booking = new Booking("b2", BookingStatus.REQUESTED);
        billing.bookingActions.behaviour = (TransitionContext<Booking, BookingStatus, BookingTransition> ctx) -> {
            if (ctx.entity() == booking) {
                ctx.cascade(BOOKINGS, owner, BookingTransition.CANCEL);
                ctx.cascade(PAYMENTS, deposit, PaymentTransition.HOLD);
            }
            return ActionOutcome.ok();
        };
        try (MachineRegistry registry = billing.registry()) {
            StateMachine<Booking, BookingStatus, BookingTransition> bookings = registry.of(BOOKINGS);
            TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                    () -> bookings.execute(booking, BookingTransition.CANCEL));
            assertEquals(CallPhase.ACTION, error.phase());
            assertEquals("transition aborted in Payment/PaymentStatus AUTHORIZING -HOLD-> ?: rejected inside a cascade"
                    + " by detached sub-machine: the parent is not in DEPOSIT or BALANCE"
                    + " [route Booking/BookingStatus#b2]", error.getMessage());
            assertEquals(BookingStatus.DEPOSIT, owner.status());
            assertEquals(PaymentStatus.AUTHORIZING, deposit.status());
        }
    }

    /** A binding's exits leave the node only when its sub-machine ends: requested from the node, they are refused. */
    @Test
    void anExitIsReservedFromItsNode() {
        Booking deposit = new Booking("b1", BookingStatus.DEPOSIT)
                .attachDeposit(new Payment("d1", PaymentStatus.AUTHORIZING));
        Booking balance = new Booking("b2", BookingStatus.BALANCE)
                .attachBalance(new Payment("d2", PaymentStatus.AUTHORIZING));
        try (MachineRegistry registry = billing.registry()) {
            Outcome<Booking, BookingStatus, BookingTransition> paid =
                    registry.of(BOOKINGS).execute(deposit, BookingTransition.DEPOSIT_PAID);
            Outcome.Rejected<Booking, BookingStatus, BookingTransition> rejected = paid.asRejected().orElseThrow();
            assertEquals(RejectionKind.RESERVED_EXIT, rejected.kind());
            assertEquals(OutcomeCode.RESERVED_EXIT, paid.code());
            assertEquals(Optional.of("DEPOSIT_PAID leaves DEPOSIT only when its sub-machine ends"), paid.explanation());
            assertEquals(Optional.empty(), rejected.guard());
            Outcome<Booking, BookingStatus, BookingTransition> failed =
                    registry.of(BOOKINGS).execute(balance, BookingTransition.PAYMENT_FAILED);
            assertEquals(Optional.of("PAYMENT_FAILED leaves BALANCE only when its sub-machine ends"),
                    failed.explanation());
            assertEquals(List.of(), billing.hooks);
            assertEquals(BookingStatus.DEPOSIT, deposit.status());
            assertEquals(BookingStatus.BALANCE, balance.status());
        }
    }

    @Test
    void aReservedExitInsideACascadeAbortsTheCall() {
        Booking owner = new Booking("b1", BookingStatus.DEPOSIT)
                .attachDeposit(new Payment("d1", PaymentStatus.AUTHORIZING));
        Booking booking = new Booking("b2", BookingStatus.REQUESTED);
        billing.bookingActions.behaviour = (TransitionContext<Booking, BookingStatus, BookingTransition> ctx) -> {
            ctx.cascade(BOOKINGS, owner, BookingTransition.DEPOSIT_PAID);
            return ActionOutcome.ok();
        };
        try (MachineRegistry registry = billing.registry()) {
            StateMachine<Booking, BookingStatus, BookingTransition> bookings = registry.of(BOOKINGS);
            TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                    () -> bookings.execute(booking, BookingTransition.CANCEL));
            assertEquals(CallPhase.ACTION, error.phase());
            assertEquals("transition aborted in Booking/BookingStatus DEPOSIT -DEPOSIT_PAID-> BALANCE: rejected"
                    + " inside a cascade by reserved exit: DEPOSIT_PAID leaves DEPOSIT only when its sub-machine ends"
                    + " [route Booking/BookingStatus#b2]", error.getMessage());
            assertEquals(BookingStatus.DEPOSIT, owner.status());
        }
    }

    /**
     * The risk check binds AUTHORIZING and maps its terminals to HOLD and DECLINE: both are reserved from AUTHORIZING
     * only, so DECLINE from HELD runs.
     */
    @Test
    void anExitIdentifierIsReservedOnlyFromItsNode() {
        Billing risky = Billing.withRiskCheck();
        Payment authorizing = new Payment("p1", PaymentStatus.AUTHORIZING);
        Payment held = new Payment("p2", PaymentStatus.HELD);
        try (MachineRegistry registry = risky.registry()) {
            StateMachine<Payment, PaymentStatus, PaymentTransition> payments = registry.of(PAYMENTS);
            Outcome<Payment, PaymentStatus, PaymentTransition> hold =
                    payments.execute(authorizing, PaymentTransition.HOLD);
            assertEquals(OutcomeCode.RESERVED_EXIT, hold.code());
            assertEquals(Optional.of("HOLD leaves AUTHORIZING only when its sub-machine ends"), hold.explanation());
            Outcome<Payment, PaymentStatus, PaymentTransition> decline =
                    payments.execute(held, PaymentTransition.DECLINE);
            assertEquals(OutcomeCode.APPLIED, decline.code());
            assertEquals(PaymentStatus.AUTHORIZING, authorizing.status());
            assertEquals(PaymentStatus.DECLINED, held.status());
        }
    }

    /** The payment is the booking's deposit and the ledger's settlement, and both are in their bound nodes. */
    @Test
    void twoActiveParentsAreAmbiguous() {
        Payment payment = new Payment("p1", PaymentStatus.AUTHORIZING);
        new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(payment);
        Ledger ledger = new Ledger("l1", LedgerStatus.SETTLING);
        ledger.settlement = Optional.of(payment);
        ledgers.add(ledger);
        try (MachineRegistry registry = registryWithLedgers()) {
            StateMachine<Payment, PaymentStatus, PaymentTransition> payments = registry.of(PAYMENTS);
            TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                    () -> payments.execute(payment, PaymentTransition.HOLD));
            assertEquals(CallPhase.RESOLVE, error.phase());
            AmbiguousParentException cause = assertInstanceOf(AmbiguousParentException.class, error.getCause());
            assertEquals(PAYMENTS, cause.child());
            assertEquals(List.of(BOOKINGS, LEDGERS), cause.parents());
            assertEquals(List.of(BookingStatus.DEPOSIT, LedgerStatus.SETTLING), cause.nodes());
            assertEquals("transition aborted in Payment/PaymentStatus AUTHORIZING -HOLD-> ?: Payment/PaymentStatus is"
                    + " bound to two active parents: Booking/BookingStatus in DEPOSIT and Ledger/LedgerStatus in"
                    + " SETTLING", error.getMessage());
            assertEquals(PaymentStatus.AUTHORIZING, payment.status());
        }
    }

    /** parentOf breaks its contract when it returns null instead of an Optional. */
    @Test
    void aParentOfThatReturnsNullAborts() {
        Payment deposit = new Payment("d1", PaymentStatus.AUTHORIZING);
        new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(deposit);
        billing.bookingOf = (Payment payment) -> null;
        try (MachineRegistry registry = billing.registry()) {
            StateMachine<Payment, PaymentStatus, PaymentTransition> payments = registry.of(PAYMENTS);
            TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                    () -> payments.execute(deposit, PaymentTransition.HOLD));
            assertEquals(CallPhase.RESOLVE, error.phase());
            assertEquals(StatesWritten.NONE, error.statesWritten());
            assertEquals("transition aborted in Payment/PaymentStatus AUTHORIZING -HOLD-> ?: the parentOf of"
                    + " Booking/BookingStatus.DEPOSIT returned null", error.getMessage());
            assertEquals(Optional.empty(), Optional.ofNullable(error.getCause()));
            assertEquals(PaymentStatus.AUTHORIZING, deposit.status());
        }
    }

    /** What parentOf throws is the cause, unwrapped from CompletionException. */
    @Test
    void aParentOfThatThrowsAbortsWithWhatItThrew() {
        Payment deposit = new Payment("d1", PaymentStatus.AUTHORIZING);
        IllegalStateException failure = new IllegalStateException("the ledger is down");
        billing.bookingOf = (Payment payment) -> {
            throw new CompletionException(failure);
        };
        try (MachineRegistry registry = billing.registry()) {
            StateMachine<Payment, PaymentStatus, PaymentTransition> payments = registry.of(PAYMENTS);
            TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                    () -> payments.execute(deposit, PaymentTransition.HOLD));
            assertEquals(CallPhase.RESOLVE, error.phase());
            assertSame(failure, error.getCause());
            assertEquals("transition aborted in Payment/PaymentStatus AUTHORIZING -HOLD-> ?: the parentOf of"
                    + " Booking/BookingStatus.DEPOSIT threw java.lang.IllegalStateException: the ledger is down",
                    error.getMessage());
        }
    }

    @Test
    void anErrorThatAParentOfThrowsPropagates() {
        Payment deposit = new Payment("d1", PaymentStatus.AUTHORIZING);
        AssertionError broken = new AssertionError("broken lookup");
        billing.bookingOf = (Payment payment) -> {
            throw broken;
        };
        try (MachineRegistry registry = billing.registry()) {
            StateMachine<Payment, PaymentStatus, PaymentTransition> payments = registry.of(PAYMENTS);
            assertSame(broken, assertThrows(AssertionError.class,
                    () -> payments.execute(deposit, PaymentTransition.HOLD)));
            assertEquals(PaymentStatus.AUTHORIZING, deposit.status());
        }
    }

    @Test
    void aChildOfThatReturnsNullDuringTheCheckAborts() {
        Payment deposit = new Payment("d1", PaymentStatus.AUTHORIZING);
        new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(deposit);
        billing.depositOf = (Booking booking) -> null;
        try (MachineRegistry registry = billing.registry()) {
            StateMachine<Payment, PaymentStatus, PaymentTransition> payments = registry.of(PAYMENTS);
            TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                    () -> payments.execute(deposit, PaymentTransition.HOLD));
            assertEquals(CallPhase.RESOLVE, error.phase());
            assertEquals("transition aborted in Payment/PaymentStatus AUTHORIZING -HOLD-> ?: the childOf of"
                    + " Booking/BookingStatus.DEPOSIT returned null", error.getMessage());
        }
    }

    @Test
    void aChildOfThatThrowsDuringTheCheckAborts() {
        Payment deposit = new Payment("d1", PaymentStatus.AUTHORIZING);
        new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(deposit);
        IllegalStateException failure = new IllegalStateException("the ledger is down");
        billing.depositOf = (Booking booking) -> {
            throw failure;
        };
        try (MachineRegistry registry = billing.registry()) {
            StateMachine<Payment, PaymentStatus, PaymentTransition> payments = registry.of(PAYMENTS);
            TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                    () -> payments.execute(deposit, PaymentTransition.HOLD));
            assertEquals(CallPhase.RESOLVE, error.phase());
            assertSame(failure, error.getCause());
            assertEquals("transition aborted in Payment/PaymentStatus AUTHORIZING -HOLD-> ?: the childOf of"
                    + " Booking/BookingStatus.DEPOSIT threw java.lang.IllegalStateException: the ledger is down",
                    error.getMessage());
        }
    }

    /** The lookups run inside the root, under its reentrancy marker: a transition they start is refused. */
    @Test
    void aLookupThatStartsATransitionAbortsTheCall() {
        Payment deposit = new Payment("d1", PaymentStatus.AUTHORIZING);
        Booking booking = new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(deposit);
        try (MachineRegistry registry = billing.registry()) {
            StateMachine<Payment, PaymentStatus, PaymentTransition> payments = registry.of(PAYMENTS);
            billing.bookingOf = (Payment payment) -> {
                registry.of(BOOKINGS).execute(booking, BookingTransition.CANCEL);
                return Optional.of(booking);
            };
            TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                    () -> payments.execute(deposit, PaymentTransition.HOLD));
            assertEquals(CallPhase.RESOLVE, error.phase());
            assertInstanceOf(ReentrantTransitionException.class, error.getCause());
            assertEquals(BookingStatus.DEPOSIT, booking.status());
            assertEquals(PaymentStatus.AUTHORIZING, deposit.status());
        }
    }

    /** The parent's state is an input: a null one is refused, as a root's or a cascaded peer's is. */
    @Test
    void aParentWithAnUninitializedStateAborts() {
        Payment deposit = new Payment("d1", PaymentStatus.AUTHORIZING);
        new Booking("b1", null).attachDeposit(deposit);
        try (MachineRegistry registry = billing.registry()) {
            StateMachine<Payment, PaymentStatus, PaymentTransition> payments = registry.of(PAYMENTS);
            TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                    () -> payments.execute(deposit, PaymentTransition.HOLD));
            assertEquals(CallPhase.RESOLVE, error.phase());
            UninitializedStateException cause = assertInstanceOf(UninitializedStateException.class, error.getCause());
            assertEquals("transition aborted in Payment/PaymentStatus AUTHORIZING -HOLD-> ?: checking the binding"
                    + " Booking/BookingStatus.DEPOSIT threw " + cause, error.getMessage());
        }
    }

    /**
     * Review Focus #1, an ORM-style lookup: childOf answers another instance with the identity of the payment the
     * caller passed. The call fails with AmbiguousIdentityException as the cause of its abort, never as DETACHED.
     */
    @Test
    void aChildOfThatAnswersAnotherInstanceOfTheChildIsAmbiguous() {
        Payment deposit = new Payment("d1", PaymentStatus.AUTHORIZING);
        new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(deposit);
        Payment loaded = new Payment("d1", PaymentStatus.AUTHORIZING);
        billing.depositOf = (Booking booking) -> Optional.of(loaded);
        try (MachineRegistry registry = billing.registry()) {
            StateMachine<Payment, PaymentStatus, PaymentTransition> payments = registry.of(PAYMENTS);
            TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                    () -> payments.execute(deposit, PaymentTransition.HOLD));
            assertEquals(CallPhase.RESOLVE, error.phase());
            AmbiguousIdentityException cause = assertInstanceOf(AmbiguousIdentityException.class, error.getCause());
            assertSame(deposit, cause.first());
            assertSame(loaded, cause.second());
            assertEquals(PaymentStatus.AUTHORIZING, deposit.status());
        }
    }

    /**
     * Inside a cascade a failed check aborts in ACTION and poisons the transaction: the action that cascaded catches
     * the abort and returns ok, and the root still throws that very abort.
     */
    @Test
    void aFailedCheckInsideACascadePoisonsTheTransaction() {
        Payment deposit = new Payment("d1", PaymentStatus.AUTHORIZING);
        Booking booking = new Booking("b1", BookingStatus.REQUESTED);
        IllegalStateException failure = new IllegalStateException("the ledger is down");
        billing.bookingOf = (Payment payment) -> {
            throw failure;
        };
        List<RuntimeException> caught = new ArrayList<>();
        billing.bookingActions.behaviour = (TransitionContext<Booking, BookingStatus, BookingTransition> ctx) -> {
            try {
                ctx.cascade(PAYMENTS, deposit, PaymentTransition.HOLD);
            } catch (TransitionAbortedException e) {
                caught.add(e);
            }
            return ActionOutcome.ok();
        };
        try (MachineRegistry registry = billing.registry()) {
            StateMachine<Booking, BookingStatus, BookingTransition> bookings = registry.of(BOOKINGS);
            TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                    () -> bookings.execute(booking, BookingTransition.CANCEL));
            assertSame(single(caught), error);
            assertEquals(CallPhase.ACTION, error.phase());
            assertSame(failure, error.getCause());
            assertEquals(BookingStatus.REQUESTED, booking.status());
        }
    }

    /**
     * The booking's watch selects BOOK, and the binding check refuses it as in a direct call: the ledger that holds
     * the booking is not in HOLDING.
     */
    @Test
    void reevaluatingADetachedChildIsRejectedAsDetached() {
        Payment awaited = new Payment("p1", PaymentStatus.AUTHORIZING);
        Booking booking = new Booking("b1", BookingStatus.REQUESTED).awaits(awaited);
        Ledger ledger = new Ledger("l1", LedgerStatus.OPEN);
        ledger.held = Optional.of(booking);
        ledgers.add(ledger);
        try (MachineRegistry registry = registryWithLedgers()) {
            Outcome<Booking, BookingStatus, BookingTransition> outcome = registry.of(BOOKINGS).reevaluate(booking);
            assertEquals(OutcomeCode.DETACHED, outcome.code());
            assertEquals(Optional.of(BookingTransition.BOOK), outcome.requestedTransition());
            assertEquals(Optional.of("the parent is not in HOLDING"), outcome.explanation());
            assertEquals(List.of(), billing.bookingActions.calls);
            assertEquals(BookingStatus.REQUESTED, booking.status());
        }
    }
}
