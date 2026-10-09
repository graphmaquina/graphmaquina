package io.github.graphmaquina.fixtures;

import static io.github.graphmaquina.api.WatchCondition.anyIn;

import io.github.graphmaquina.api.CommitContext;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.dsl.GraphBuilder;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.runtime.MachineRegistry;
import io.github.graphmaquina.runtime.StateMachines;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Sub-machine fixture. A booking takes a deposit and then a balance, and the booking graph binds the same payment
 * machine to both states, {@code DEPOSIT} and {@code BALANCE}. {@link #withRiskCheck()} also binds a risk check to the
 * payment's {@code AUTHORIZING} state; the risk check governs another state of the payment itself.
 * <ul>
 *   <li>The lookups of the bindings read {@link #depositOf}, {@link #balanceOf} and {@link #bookingOf} each time they
 *       run, so a test replaces a field to change what a lookup returns.</li>
 *   <li>The booking graph watches payments from {@code REQUESTED}: a booking that awaits a payment executes
 *       {@code BOOK} once that payment is {@code AUTHORIZING}.</li>
 *   <li>Every action runs {@link #bookingActions} or {@link #paymentActions}. Every edge runs the hook {@code Audit},
 *       which records its hop in {@link #hooks} and then runs {@link #afterHook}.</li>
 *   <li>Each entity starts with every state it holds initialized.</li>
 * </ul>
 */
public final class Billing {

    /** States of a booking. */
    public enum BookingStatus { REQUESTED, DEPOSIT, BALANCE, CONFIRMED, CANCELLED }

    /** Identifiers of the booking machine. */
    public enum BookingTransition { BOOK, PAY_IN_FULL, RESTART, DEPOSIT_PAID, BALANCE_PAID, PAYMENT_FAILED, CANCEL }

    /** States of a payment: three terminal outcomes of one authorization, which may be held first. */
    public enum PaymentStatus { AUTHORIZING, HELD, CAPTURED, DECLINED, VOIDED }

    /** Identifiers of the payment machine. */
    public enum PaymentTransition { HOLD, CAPTURE, DECLINE, VOID }

    /** States of the risk check of a payment. */
    public enum RiskStatus { SCORING, CLEARED, FLAGGED }

    /** Identifiers of the risk check. */
    public enum RiskTransition { CLEAR, FLAG }

    public static final MachineKey<Booking, BookingStatus, BookingTransition> BOOKINGS =
            MachineKey.of(Booking.class, BookingStatus.class, BookingTransition.class);
    public static final MachineKey<Payment, PaymentStatus, PaymentTransition> PAYMENTS =
            MachineKey.of(Payment.class, PaymentStatus.class, PaymentTransition.class);
    public static final MachineKey<Payment, RiskStatus, RiskTransition> RISKS =
            MachineKey.of(Payment.class, RiskStatus.class, RiskTransition.class);

    /** A booking: its status, its deposit and balance payments, and the payment it awaits, if any. */
    public static final class Booking {

        private final String id;
        private BookingStatus status;
        private Optional<Payment> deposit = Optional.empty();
        private Optional<Payment> balance = Optional.empty();
        private Optional<Payment> awaited = Optional.empty();

        public Booking(String id, BookingStatus status) {
            this.id = id;
            this.status = status;
        }

        public String id() { return id; }

        public BookingStatus status() { return status; }

        public void setStatus(BookingStatus status) { this.status = status; }

        public Optional<Payment> deposit() { return deposit; }

        public Optional<Payment> balance() { return balance; }

        /** The payment this booking awaits, as the collection its watch reads. */
        public List<Payment> awaited() { return awaited.map(List::of).orElse(List.of()); }

        /** Makes {@code payment} this booking's deposit, and this booking the payment's. */
        public Booking attachDeposit(Payment payment) {
            deposit = Optional.of(payment);
            payment.booking = Optional.of(this);
            return this;
        }

        /** Makes {@code payment} this booking's balance, and this booking the payment's. */
        public Booking attachBalance(Payment payment) {
            balance = Optional.of(payment);
            payment.booking = Optional.of(this);
            return this;
        }

        /** Makes this booking await {@code payment}. */
        public Booking awaits(Payment payment) {
            awaited = Optional.of(payment);
            payment.awaitedBy = Optional.of(this);
            return this;
        }

        @Override
        public String toString() { return "Booking#" + id; }
    }

    /**
     * A payment: its status, the state of its risk check, the booking it belongs to and the booking that awaits it.
     * The risk check starts in {@code SCORING}.
     */
    public static final class Payment {

        private final String id;
        private PaymentStatus status;
        private RiskStatus risk = RiskStatus.SCORING;
        private Optional<Booking> booking = Optional.empty();
        private Optional<Booking> awaitedBy = Optional.empty();

        public Payment(String id, PaymentStatus status) {
            this.id = id;
            this.status = status;
        }

        public String id() { return id; }

        public PaymentStatus status() { return status; }

        public void setStatus(PaymentStatus status) { this.status = status; }

        public RiskStatus risk() { return risk; }

        public void setRisk(RiskStatus risk) { this.risk = risk; }

        public Optional<Booking> booking() { return booking; }

        public Optional<Booking> awaitedBy() { return awaitedBy; }

        @Override
        public String toString() { return "Payment#" + id; }
    }

    /** What the binding of {@code DEPOSIT} reaches from a booking; {@code Booking::deposit} unless replaced. */
    public Function<Booking, Optional<Payment>> depositOf = Booking::deposit;
    /** What the binding of {@code BALANCE} reaches from a booking; {@code Booking::balance} unless replaced. */
    public Function<Booking, Optional<Payment>> balanceOf = Booking::balance;
    /** What both bindings reach from a payment; {@code Payment::booking} unless replaced. */
    public Function<Payment, Optional<Booking>> bookingOf = Payment::booking;
    /** The action of every booking edge that declares one. */
    public final Probe<Booking, BookingStatus, BookingTransition> bookingActions = new Probe<>();
    /** The action of every payment edge that declares one. */
    public final Probe<Payment, PaymentStatus, PaymentTransition> paymentActions = new Probe<>();
    /** Every hop whose hook ran, as {@code entity:from-identifier>to}, in order. */
    public final List<String> hooks = new ArrayList<>();
    /** Runs after the hook recorded its hop; a test replaces it to make the hook throw. */
    public Consumer<String> afterHook = (String hop) -> { };

    public final GraphDefinition<Payment, RiskStatus, RiskTransition> risks;
    public final GraphDefinition<Payment, PaymentStatus, PaymentTransition> payments;
    public final GraphDefinition<Booking, BookingStatus, BookingTransition> bookings;
    private final boolean riskCheck;

    /** Bookings over payments, with no risk check. */
    public Billing() {
        this(false);
    }

    private Billing(boolean riskCheck) {
        this.riskCheck = riskCheck;
        this.risks = risks();
        this.payments = payments();
        this.bookings = bookings();
    }

    /** Bookings over payments, whose {@code AUTHORIZING} state binds the risk check of the payment itself. */
    public static Billing withRiskCheck() {
        return new Billing(true);
    }

    public StateMachines.Builder<Booking, BookingStatus, BookingTransition> bookingMachine() {
        return StateMachines.from(bookings).state(Booking::status, Booking::setStatus).identity(Booking::id);
    }

    public StateMachines.Builder<Payment, PaymentStatus, PaymentTransition> paymentMachine() {
        return StateMachines.from(payments).state(Payment::status, Payment::setStatus).identity(Payment::id);
    }

    public StateMachines.Builder<Payment, RiskStatus, RiskTransition> riskMachine() {
        return StateMachines.from(risks).state(Payment::risk, Payment::setRisk).identity(Payment::id);
    }

    /** The booking and payment machines, and the risk check when this fixture binds it. */
    public MachineRegistry registry() {
        MachineRegistry.Builder builder = MachineRegistry.builder().add(bookingMachine()).add(paymentMachine());
        if (riskCheck) {
            builder.add(riskMachine());
        }
        return builder.build();
    }

    /** The hook {@code Audit}: records the hop, then runs {@link #afterHook}. */
    private <E, S extends Enum<S>, T extends Enum<T>> BiConsumer<E, CommitContext<S, T>> audit() {
        return (E entity, CommitContext<S, T> context) -> {
            String hop = entity + ":" + context.from() + "-" + context.transition() + ">" + context.to();
            hooks.add(hop);
            afterHook.accept(hop);
        };
    }

    private GraphDefinition<Payment, RiskStatus, RiskTransition> risks() {
        BiConsumer<Payment, CommitContext<RiskStatus, RiskTransition>> audit = audit();
        return StateGraph.define(RISKS)
                .initial(RiskStatus.SCORING).terminal(RiskStatus.CLEARED).terminal(RiskStatus.FLAGGED)
                .statePolicy(RiskStatus.SCORING, "a risk score clears or flags the payment")
                .transition(RiskTransition.CLEAR, RiskStatus.SCORING, RiskStatus.CLEARED)
                    .afterCommitWith("Audit", audit).endTransition()
                .transition(RiskTransition.FLAG, RiskStatus.SCORING, RiskStatus.FLAGGED)
                    .afterCommitWith("Audit", audit).endTransition()
                .build();
    }

    /** {@code AUTHORIZING} and {@code HELD} each end in {@code CAPTURED}, {@code DECLINED} or {@code VOIDED}. */
    private GraphDefinition<Payment, PaymentStatus, PaymentTransition> payments() {
        BiConsumer<Payment, CommitContext<PaymentStatus, PaymentTransition>> audit = audit();
        GraphBuilder<Payment, PaymentStatus, PaymentTransition> graph = StateGraph.define(PAYMENTS)
                .initial(PaymentStatus.AUTHORIZING)
                .terminal(PaymentStatus.CAPTURED).terminal(PaymentStatus.DECLINED).terminal(PaymentStatus.VOIDED)
                .statePolicy(PaymentStatus.AUTHORIZING, "an authorization is held, captured, declined or voided")
                .statePolicy(PaymentStatus.HELD, "a hold is captured, declined or voided")
                .transition(PaymentTransition.HOLD, PaymentStatus.AUTHORIZING, PaymentStatus.HELD)
                    .afterCommitWith("Audit", audit).endTransition()
                .transition(PaymentTransition.CAPTURE, PaymentStatus.AUTHORIZING, PaymentStatus.CAPTURED)
                    .action("Capture", paymentActions).afterCommitWith("Audit", audit).endTransition()
                .transition(PaymentTransition.CAPTURE, PaymentStatus.HELD, PaymentStatus.CAPTURED)
                    .action("Capture", paymentActions).afterCommitWith("Audit", audit).endTransition()
                .transition(PaymentTransition.DECLINE, PaymentStatus.AUTHORIZING, PaymentStatus.DECLINED)
                    .afterCommitWith("Audit", audit).endTransition()
                .transition(PaymentTransition.DECLINE, PaymentStatus.HELD, PaymentStatus.DECLINED)
                    .afterCommitWith("Audit", audit).endTransition()
                .transition(PaymentTransition.VOID, PaymentStatus.AUTHORIZING, PaymentStatus.VOIDED)
                    .afterCommitWith("Audit", audit).endTransition()
                .transition(PaymentTransition.VOID, PaymentStatus.HELD, PaymentStatus.VOIDED)
                    .afterCommitWith("Audit", audit).endTransition();
        if (riskCheck) {
            graph.submachine(PaymentStatus.AUTHORIZING, risks)
                    .exit(RiskStatus.CLEARED, PaymentTransition.HOLD)
                    .exit(RiskStatus.FLAGGED, PaymentTransition.DECLINE)
                    .endSubmachine();
        }
        return graph.build();
    }

    /**
     * {@code REQUESTED} books into {@code DEPOSIT}, pays in full into {@code BALANCE}, falling back to {@code BOOK}
     * when that action fails, or cancels. {@code DEPOSIT} restarts itself, goes on to {@code BALANCE} or cancels,
     * and {@code BALANCE} confirms or cancels.
     */
    private GraphDefinition<Booking, BookingStatus, BookingTransition> bookings() {
        BiConsumer<Booking, CommitContext<BookingStatus, BookingTransition>> audit = audit();
        return StateGraph.define(BOOKINGS)
                .initial(BookingStatus.REQUESTED)
                .terminal(BookingStatus.CONFIRMED).terminal(BookingStatus.CANCELLED)
                .allowsCycle(BookingStatus.DEPOSIT)
                .statePolicy(BookingStatus.REQUESTED, "a request is booked, paid in full or cancelled")
                .statePolicy(BookingStatus.DEPOSIT, "a deposit is paid, restarted or cancelled")
                .statePolicy(BookingStatus.BALANCE, "a balance is paid or cancelled")
                .transition(BookingTransition.BOOK, BookingStatus.REQUESTED, BookingStatus.DEPOSIT)
                    .action("TakeDeposit", bookingActions).afterCommitWith("Audit", audit).endTransition()
                .transition(BookingTransition.PAY_IN_FULL, BookingStatus.REQUESTED, BookingStatus.BALANCE)
                    .action("ChargeInFull", bookingActions).onFailure(BookingTransition.BOOK)
                    .afterCommitWith("Audit", audit).endTransition()
                .transition(BookingTransition.CANCEL, BookingStatus.REQUESTED, BookingStatus.CANCELLED)
                    .action("CancelBooking", bookingActions).afterCommitWith("Audit", audit).endTransition()
                .transition(BookingTransition.RESTART, BookingStatus.DEPOSIT, BookingStatus.DEPOSIT)
                    .afterCommitWith("Audit", audit).endTransition()
                .transition(BookingTransition.DEPOSIT_PAID, BookingStatus.DEPOSIT, BookingStatus.BALANCE)
                    .afterCommitWith("Audit", audit).endTransition()
                .transition(BookingTransition.PAYMENT_FAILED, BookingStatus.DEPOSIT, BookingStatus.CANCELLED)
                    .afterCommitWith("Audit", audit).endTransition()
                .transition(BookingTransition.CANCEL, BookingStatus.DEPOSIT, BookingStatus.CANCELLED)
                    .action("CancelBooking", bookingActions).afterCommitWith("Audit", audit).endTransition()
                .transition(BookingTransition.BALANCE_PAID, BookingStatus.BALANCE, BookingStatus.CONFIRMED)
                    .afterCommitWith("Audit", audit).endTransition()
                .transition(BookingTransition.PAYMENT_FAILED, BookingStatus.BALANCE, BookingStatus.CANCELLED)
                    .afterCommitWith("Audit", audit).endTransition()
                .transition(BookingTransition.CANCEL, BookingStatus.BALANCE, BookingStatus.CANCELLED)
                    .action("CancelBooking", bookingActions).afterCommitWith("Audit", audit).endTransition()
                .watch(BookingStatus.REQUESTED, PAYMENTS, Booking::awaited, Payment::awaitedBy)
                    .when(anyIn(PaymentStatus.AUTHORIZING)).execute(BookingTransition.BOOK)
                    .endWatch()
                .submachine(BookingStatus.DEPOSIT, payments, (Booking booking) -> depositOf.apply(booking),
                        (Payment payment) -> bookingOf.apply(payment))
                    .exit(PaymentStatus.CAPTURED, BookingTransition.DEPOSIT_PAID)
                    .exit(PaymentStatus.DECLINED, BookingTransition.PAYMENT_FAILED)
                    .exit(PaymentStatus.VOIDED, BookingTransition.PAYMENT_FAILED)
                    .endSubmachine()
                .submachine(BookingStatus.BALANCE, payments, (Booking booking) -> balanceOf.apply(booking),
                        (Payment payment) -> bookingOf.apply(payment))
                    .exit(PaymentStatus.CAPTURED, BookingTransition.BALANCE_PAID)
                    .exit(PaymentStatus.DECLINED, BookingTransition.PAYMENT_FAILED)
                    .exit(PaymentStatus.VOIDED, BookingTransition.PAYMENT_FAILED)
                    .endSubmachine()
                .build();
    }
}
