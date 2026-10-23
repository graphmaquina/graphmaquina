package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.Billing.BOOKINGS;
import static io.github.graphmaquina.fixtures.Billing.PAYMENTS;
import static io.github.graphmaquina.fixtures.Billing.RISKS;
import static io.github.graphmaquina.fixtures.RecordTexts.described;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.CascadePolicy;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.OutcomeCode;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.StatePolicy;
import io.github.graphmaquina.api.StatesWritten;
import io.github.graphmaquina.api.SubmachineSpec;
import io.github.graphmaquina.api.SubmachineStart;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.api.WatchSpec;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.error.AfterCommitException;
import io.github.graphmaquina.error.CascadeDepthExceededException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.error.UnhandledFailureException;
import io.github.graphmaquina.fixtures.Billing;
import io.github.graphmaquina.fixtures.Billing.Booking;
import io.github.graphmaquina.fixtures.Billing.BookingStatus;
import io.github.graphmaquina.fixtures.Billing.BookingTransition;
import io.github.graphmaquina.fixtures.Billing.Payment;
import io.github.graphmaquina.fixtures.Billing.PaymentStatus;
import io.github.graphmaquina.fixtures.Billing.PaymentTransition;
import io.github.graphmaquina.fixtures.Billing.RiskStatus;
import io.github.graphmaquina.fixtures.Billing.RiskTransition;
import io.github.graphmaquina.graph.DirectedGraph;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * The atomic exit: a child whose parent held it when its step began, and whose step ends in a terminal state with that
 * parent still in the bound node and not entered again by that step, runs the exit that terminal selects, as a step of
 * the parent at the next depth, in the same unit of work, once the child's own step is no longer in flight. An exit
 * runs its guards, its action and its failure route as any step; a rejection or an unhandled failure inside it aborts
 * the whole call. A parent that enters a node whose child already stands in a terminal state exits at the end of its
 * own step. A parent in flight, leaving the node or entering it, never exits from the child's step, and an exit that
 * ends its parent in a terminal state runs the grandparent's exit in turn. How a parent that leaves the node or
 * enters it again changes which exit runs is read in {@link SubmachineExitReentryTest}.
 */
class SubmachineExitTest {

    /** States of a ticket: issuing it starts its receipt, which is sent as soon as it starts. */
    enum TicketStatus { OPEN, ISSUING, DONE }

    /** Identifiers of the ticket machine. */
    enum TicketTransition { ISSUE, FINISH }

    /** The only state of a receipt, its initial and terminal state. */
    enum ReceiptStatus { SENT }

    /** Identifiers of the receipt machine; its graph declares no edge. */
    enum ReceiptTransition { RESEND }

    static final MachineKey<Ticket, TicketStatus, TicketTransition> TICKETS =
            MachineKey.of(Ticket.class, TicketStatus.class, TicketTransition.class);
    static final MachineKey<Ticket, ReceiptStatus, ReceiptTransition> RECEIPTS =
            MachineKey.of(Ticket.class, ReceiptStatus.class, ReceiptTransition.class);

    /** A ticket and its receipt, two states of one entity. */
    static final class Ticket {

        private final String id;
        private TicketStatus status = TicketStatus.OPEN;
        private ReceiptStatus receipt = ReceiptStatus.SENT;

        Ticket(String id) {
            this.id = id;
        }

        String id() { return id; }

        TicketStatus status() { return status; }

        void setStatus(TicketStatus status) { this.status = status; }

        ReceiptStatus receipt() { return receipt; }

        void setReceipt(ReceiptStatus receipt) { this.receipt = receipt; }

        @Override
        public String toString() { return "Ticket#" + id; }
    }

    /**
     * The booking graph by hand, as an application could write it, whose DEPOSIT binding maps VOIDED to
     * {@code voided}, or to no exit when it is empty. Only a hand-written graph can hold either: the DSL refuses both.
     */
    private static final class HandWritten implements GraphDefinition<Booking, BookingStatus, BookingTransition> {

        private final GraphDefinition<Booking, BookingStatus, BookingTransition> graph;
        private final Optional<BookingTransition> voided;

        HandWritten(GraphDefinition<Booking, BookingStatus, BookingTransition> graph,
                    Optional<BookingTransition> voided) {
            this.graph = graph;
            this.voided = voided;
        }

        private <P, Q extends Enum<Q>, U extends Enum<U>>
                SubmachineSpec<Booking, BookingStatus, BookingTransition, P, Q, U> rewritten(
                        SubmachineSpec<Booking, BookingStatus, BookingTransition, P, Q, U> spec) {
            Map<Q, BookingTransition> exits = new LinkedHashMap<>();
            spec.exits().forEach((Q terminal, BookingTransition exit) -> {
                if (terminal != PaymentStatus.VOIDED) {
                    exits.put(terminal, exit);
                } else if (voided.isPresent()) {
                    exits.put(terminal, voided.get());
                }
            });
            return new SubmachineSpec<>(spec.state(), spec.child(), spec.childOf(), spec.parentOf(), exits);
        }

        @Override
        public MachineKey<Booking, BookingStatus, BookingTransition> key() { return graph.key(); }

        @Override
        public BookingStatus initial() { return graph.initial(); }

        @Override
        public Set<BookingStatus> terminals() { return graph.terminals(); }

        @Override
        public DirectedGraph<BookingStatus, BookingTransition> graph() { return graph.graph(); }

        @Override
        public List<TransitionSpec<Booking, BookingStatus, BookingTransition>> transitions() {
            return graph.transitions();
        }

        @Override
        public List<TransitionSpec<Booking, BookingStatus, BookingTransition>> outgoingFrom(BookingStatus state) {
            return graph.outgoingFrom(state);
        }

        @Override
        public Optional<TransitionSpec<Booking, BookingStatus, BookingTransition>> findTransition(
                BookingStatus from, BookingTransition transition) {
            return graph.findTransition(from, transition);
        }

        @Override
        public StatePolicy<BookingStatus, BookingTransition> policy(BookingStatus state) { return graph.policy(state); }

        @Override
        public Optional<WatchSpec<Booking, BookingStatus, BookingTransition, ?, ?, ?>> watch(BookingStatus state) {
            return graph.watch(state);
        }

        @Override
        public Optional<SubmachineSpec<Booking, BookingStatus, BookingTransition, ?, ?, ?>> submachine(
                BookingStatus state) {
            Optional<SubmachineSpec<Booking, BookingStatus, BookingTransition, ?, ?, ?>> spec = graph.submachine(state);
            if (state != BookingStatus.DEPOSIT) {
                return spec;
            }
            return Optional.of(rewritten(spec.orElseThrow()));
        }

        @Override
        public boolean hasAsync() { return graph.hasAsync(); }
    }

    private final Billing billing = new Billing();

    private static <T> T single(List<T> values) {
        assertEquals(1, values.size(), values.toString());
        return values.get(0);
    }

    /** The start of a balance payment that was authorizing, when the exit DEPOSIT_PAID enters BALANCE. */
    private static SubmachineStart<Payment, PaymentStatus, PaymentTransition> balanceStart(Payment balance,
                                                                                           Booking booking, int depth) {
        return new SubmachineStart<>(PAYMENTS, balance, PaymentStatus.AUTHORIZING, PaymentStatus.AUTHORIZING, BOOKINGS,
                booking, BookingStatus.BALANCE, depth);
    }

    /** A booking action whose CANCEL cascades the capture of {@code deposit}; every other edge just succeeds. */
    private static Function<TransitionContext<Booking, BookingStatus, BookingTransition>, ActionOutcome>
            cancellingCaptures(Payment deposit) {
        return (TransitionContext<Booking, BookingStatus, BookingTransition> ctx) -> {
            if (ctx.transition() == BookingTransition.CANCEL) {
                ctx.cascade(PAYMENTS, deposit, PaymentTransition.CAPTURE);
            }
            return ActionOutcome.ok();
        };
    }

    /**
     * The capture ends the deposit, which selects DEPOSIT_PAID: the booking moves to BALANCE in the same commit, and
     * the balance payment starts. The call's outcome is the capture's; its records hold both hops in log order, and
     * the hook of each hop runs. The exit's guard and action get no input, whatever the call was given.
     */
    @Test
    void aChildThatEndsExitsItsParentInTheSameCommit() {
        Payment deposit = new Payment("d1", PaymentStatus.AUTHORIZING);
        Payment balance = new Payment("d2", PaymentStatus.AUTHORIZING);
        Booking booking = new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(deposit).attachBalance(balance);
        List<Optional<?>> inputs = new ArrayList<>();
        billing.paymentActions.behaviour = (TransitionContext<Payment, PaymentStatus, PaymentTransition> ctx) -> {
            inputs.add(ctx.input());
            return ActionOutcome.ok();
        };
        billing.settled.verdict = (GuardContext<Booking, BookingStatus, BookingTransition> ctx) -> {
            inputs.add(ctx.input());
            return GuardResult.pass();
        };
        billing.bookingActions.behaviour = (TransitionContext<Booking, BookingStatus, BookingTransition> ctx) -> {
            inputs.add(ctx.input());
            return ActionOutcome.ok();
        };
        try (MachineRegistry registry = billing.registry()) {
            Outcome<Payment, PaymentStatus, PaymentTransition> outcome =
                    registry.of(PAYMENTS).execute(deposit, PaymentTransition.CAPTURE, "receipt-7");
            assertEquals(List.of(Optional.of("receipt-7"), Optional.empty(), Optional.empty()), inputs);
            Outcome.Applied<Payment, PaymentStatus, PaymentTransition> applied = outcome.asApplied().orElseThrow();
            assertSame(deposit, applied.entity());
            assertEquals(PaymentStatus.CAPTURED, applied.to());
            assertEquals(List.of("Payment#d1:AUTHORIZING-CAPTURE>CAPTURED@0",
                    "Booking#b1:DEPOSIT-DEPOSIT_PAID>BALANCE@1"), described(outcome.records()));
            assertEquals(List.of(balanceStart(balance, booking, 1)), outcome.starts());
            assertEquals(List.of("Payment#d1:AUTHORIZING-CAPTURE>CAPTURED", "Booking#b1:DEPOSIT-DEPOSIT_PAID>BALANCE"),
                    billing.hooks);
            assertEquals(List.of("Booking#b1:DEPOSIT-DEPOSIT_PAID>BALANCE@1"), billing.bookingActions.calls);
            assertEquals(1, billing.settled.calls);
            assertEquals(PaymentStatus.CAPTURED, deposit.status());
            assertEquals(BookingStatus.BALANCE, booking.status());
            assertEquals(PaymentStatus.AUTHORIZING, balance.status());
        }
    }

    /** A child whose failure route ends in a terminal state exits as well: its outcome stays the routed failure. */
    @Test
    void aChildWhoseFailureRouteEndsInATerminalStateExitsToo() {
        Payment deposit = new Payment("d1", PaymentStatus.HELD);
        Booking booking = new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(deposit);
        billing.paymentActions.behaviour = (TransitionContext<Payment, PaymentStatus, PaymentTransition> ctx) ->
                ActionOutcome.failed("the card was declined");
        try (MachineRegistry registry = billing.registry()) {
            Outcome<Payment, PaymentStatus, PaymentTransition> outcome =
                    registry.of(PAYMENTS).execute(deposit, PaymentTransition.CAPTURE);
            assertEquals(OutcomeCode.FAILURE_ROUTED, outcome.code());
            assertEquals(PaymentStatus.DECLINED, outcome.recordedState());
            assertEquals(List.of("Payment#d1:HELD-DECLINE>DECLINED@0", "Booking#b1:DEPOSIT-PAYMENT_FAILED>CANCELLED@1"),
                    described(outcome.records()));
            assertEquals(BookingStatus.CANCELLED, booking.status());
        }
    }

    /**
     * The exit's action fails and its failure route CANCEL is taken: the parent moves to CANCELLED, everything
     * commits, and the child's outcome stays applied.
     */
    @Test
    void anExitWithAHandledFailureTakesItsRouteAndCommits() {
        Payment deposit = new Payment("d1", PaymentStatus.AUTHORIZING);
        Payment balance = new Payment("d2", PaymentStatus.AUTHORIZING);
        Booking booking = new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(deposit).attachBalance(balance);
        billing.bookingActions.behaviour = (TransitionContext<Booking, BookingStatus, BookingTransition> ctx) ->
                ctx.transition() == BookingTransition.DEPOSIT_PAID
                        ? ActionOutcome.failed("the ledger refused the deposit")
                        : ActionOutcome.ok();
        try (MachineRegistry registry = billing.registry()) {
            Outcome<Payment, PaymentStatus, PaymentTransition> outcome =
                    registry.of(PAYMENTS).execute(deposit, PaymentTransition.CAPTURE);
            assertEquals(OutcomeCode.APPLIED, outcome.code());
            assertEquals(List.of("Payment#d1:AUTHORIZING-CAPTURE>CAPTURED@0", "Booking#b1:DEPOSIT-CANCEL>CANCELLED@1"),
                    described(outcome.records()));
            assertEquals(List.of(), outcome.starts());
            assertEquals(List.of("Booking#b1:DEPOSIT-DEPOSIT_PAID>BALANCE@1", "Booking#b1:DEPOSIT-CANCEL>CANCELLED@1"),
                    billing.bookingActions.calls);
            assertEquals(PaymentStatus.CAPTURED, deposit.status());
            assertEquals(BookingStatus.CANCELLED, booking.status());
            assertEquals(PaymentStatus.AUTHORIZING, balance.status());
        }
    }

    /** A guard of the exit rejects: the call aborts in ACTION and nothing is written. */
    @Test
    void anExitThatAGuardRejectsAbortsTheCall() {
        Payment deposit = new Payment("d1", PaymentStatus.AUTHORIZING);
        Booking booking = new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(deposit)
                .attachBalance(new Payment("d2", PaymentStatus.AUTHORIZING));
        billing.settled.verdict = (GuardContext<Booking, BookingStatus, BookingTransition> ctx) ->
                GuardResult.reject("the deposit is not settled");
        try (MachineRegistry registry = billing.registry()) {
            StateMachine<Payment, PaymentStatus, PaymentTransition> payments = registry.of(PAYMENTS);
            TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                    () -> payments.execute(deposit, PaymentTransition.CAPTURE));
            assertEquals(CallPhase.ACTION, error.phase());
            assertEquals(StatesWritten.NONE, error.statesWritten());
            assertEquals("transition aborted in Booking/BookingStatus DEPOSIT -DEPOSIT_PAID-> BALANCE: rejected inside"
                    + " the exit of DEPOSIT by guard Settled: the deposit is not settled"
                    + " [route Booking/BookingStatus#b1]", error.getMessage());
            assertEquals(List.of(), billing.hooks);
            assertEquals(PaymentStatus.AUTHORIZING, deposit.status());
            assertEquals(BookingStatus.DEPOSIT, booking.status());
        }
    }

    /**
     * The exit's action fails and its edge has no failure route: the failure poisons the transaction, so the call
     * fails even though the action that cascaded to the payment caught it, and nothing is written.
     */
    @Test
    void anUnhandledFailureOfAnExitPoisonsTheCall() {
        Payment balance = new Payment("d2", PaymentStatus.AUTHORIZING);
        Booking owner = new Booking("b1", BookingStatus.BALANCE).attachBalance(balance);
        Booking booking = new Booking("b2", BookingStatus.REQUESTED);
        List<RuntimeException> caught = new ArrayList<>();
        billing.bookingActions.behaviour = (TransitionContext<Booking, BookingStatus, BookingTransition> ctx) -> {
            if (ctx.transition() == BookingTransition.BALANCE_PAID) {
                return ActionOutcome.failed("the ledger refused the balance");
            }
            try {
                ctx.cascade(PAYMENTS, balance, PaymentTransition.CAPTURE);
            } catch (UnhandledFailureException e) {
                caught.add(e);
            }
            return ActionOutcome.ok();
        };
        try (MachineRegistry registry = billing.registry()) {
            StateMachine<Booking, BookingStatus, BookingTransition> bookings = registry.of(BOOKINGS);
            UnhandledFailureException error = assertThrows(UnhandledFailureException.class,
                    () -> bookings.execute(booking, BookingTransition.CANCEL));
            assertSame(single(caught), error);
            assertEquals(StatesWritten.NONE, error.statesWritten());
            assertEquals(List.of(), billing.hooks);
            assertEquals(PaymentStatus.AUTHORIZING, balance.status());
            assertEquals(BookingStatus.BALANCE, owner.status());
            assertEquals(BookingStatus.REQUESTED, booking.status());
        }
    }

    /**
     * The risk check ends FLAGGED, whose exit DECLINE ends the payment, whose exit PAYMENT_FAILED cancels the booking:
     * three levels in one commit, each exit one level deeper.
     */
    @Test
    void aChainOfThreeLevelsExitsEveryParent() {
        Billing risky = Billing.withRiskCheck();
        Payment deposit = new Payment("d1", PaymentStatus.AUTHORIZING);
        Booking booking = new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(deposit);
        try (MachineRegistry registry = risky.registry()) {
            Outcome<Payment, RiskStatus, RiskTransition> outcome =
                    registry.of(RISKS).execute(deposit, RiskTransition.FLAG);
            assertEquals(RiskStatus.FLAGGED, outcome.recordedState());
            assertEquals(List.of("Payment#d1:SCORING-FLAG>FLAGGED@0", "Payment#d1:AUTHORIZING-DECLINE>DECLINED@1",
                    "Booking#b1:DEPOSIT-PAYMENT_FAILED>CANCELLED@2"), described(outcome.records()));
            assertEquals(RiskStatus.FLAGGED, deposit.risk());
            assertEquals(PaymentStatus.DECLINED, deposit.status());
            assertEquals(BookingStatus.CANCELLED, booking.status());
        }
    }

    /** Every exit is one level deeper: with maxCascadeDepth 1, the second exit of the chain aborts the call. */
    @Test
    void everyExitCountsForTheCascadeDepth() {
        Billing risky = Billing.withRiskCheck();
        Payment deposit = new Payment("d1", PaymentStatus.AUTHORIZING);
        Booking booking = new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(deposit);
        try (MachineRegistry registry = MachineRegistry.builder().maxCascadeDepth(1).add(risky.bookingMachine())
                .add(risky.paymentMachine()).add(risky.riskMachine()).build()) {
            StateMachine<Payment, RiskStatus, RiskTransition> risks = registry.of(RISKS);
            CascadeDepthExceededException error = assertThrows(CascadeDepthExceededException.class,
                    () -> risks.execute(deposit, RiskTransition.FLAG));
            assertEquals(1, error.maxDepth());
            assertEquals("transition aborted in Payment/PaymentStatus AUTHORIZING -DECLINE-> DECLINED: the cascade"
                    + " exceeds maxCascadeDepth = 1", error.getMessage());
            assertEquals(RiskStatus.SCORING, deposit.risk());
            assertEquals(PaymentStatus.AUTHORIZING, deposit.status());
            assertEquals(BookingStatus.DEPOSIT, booking.status());
        }
    }

    /**
     * The entering action captures the deposit while the booking is in flight, so the capture runs no exit. The
     * booking's own step then ends in DEPOSIT, whose child stands in a terminal state: the exit runs there.
     */
    @Test
    void aParentThatEntersANodeWhoseChildEndedExitsAtTheEndOfItsStep() {
        Payment deposit = new Payment("d1", PaymentStatus.VOIDED);
        Payment balance = new Payment("d2", PaymentStatus.AUTHORIZING);
        Booking booking = new Booking("b1", BookingStatus.REQUESTED).attachDeposit(deposit).attachBalance(balance);
        billing.bookingActions.behaviour = (TransitionContext<Booking, BookingStatus, BookingTransition> ctx) -> {
            if (ctx.transition() == BookingTransition.BOOK) {
                ctx.cascade(PAYMENTS, deposit, PaymentTransition.CAPTURE);
            }
            return ActionOutcome.ok();
        };
        try (MachineRegistry registry = billing.registry()) {
            Outcome<Booking, BookingStatus, BookingTransition> outcome =
                    registry.of(BOOKINGS).execute(booking, BookingTransition.BOOK);
            assertEquals(BookingStatus.DEPOSIT, outcome.recordedState());
            assertEquals(List.of("Booking#b1:REQUESTED-BOOK>DEPOSIT@0", "Payment#d1:AUTHORIZING-CAPTURE>CAPTURED@1",
                    "Booking#b1:DEPOSIT-DEPOSIT_PAID>BALANCE@1"), described(outcome.records()));
            assertEquals(List.of(new SubmachineStart<>(PAYMENTS, deposit, PaymentStatus.VOIDED,
                    PaymentStatus.AUTHORIZING, BOOKINGS, booking, BookingStatus.DEPOSIT, 0),
                    balanceStart(balance, booking, 1)), outcome.starts());
            assertEquals(BookingStatus.BALANCE, booking.status());
            assertEquals(PaymentStatus.CAPTURED, deposit.status());
        }
    }

    /**
     * A direct exit runs one level below the child's step that ended the child: cancelling another booking, at depth 0,
     * cascades the capture of the deposit, at depth 1, and the exit DEPOSIT_PAID runs at depth 2, which a
     * maxCascadeDepth of 2 still admits. The contexts of both actions report those depths.
     */
    @Test
    void aDirectExitRunsOneLevelBelowTheChildStepThatEndedTheChild() {
        Payment deposit = new Payment("d1", PaymentStatus.AUTHORIZING);
        Booking owner = new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(deposit)
                .attachBalance(new Payment("d2", PaymentStatus.AUTHORIZING));
        Booking booking = new Booking("b2", BookingStatus.REQUESTED);
        billing.bookingActions.behaviour = cancellingCaptures(deposit);
        try (MachineRegistry registry = MachineRegistry.builder().maxCascadeDepth(2).add(billing.bookingMachine())
                .add(billing.paymentMachine()).build()) {
            Outcome<Booking, BookingStatus, BookingTransition> outcome =
                    registry.of(BOOKINGS).execute(booking, BookingTransition.CANCEL);
            assertEquals(OutcomeCode.APPLIED, outcome.code());
            assertEquals(List.of("Booking#b2:REQUESTED-CANCEL>CANCELLED@0", "Payment#d1:AUTHORIZING-CAPTURE>CAPTURED@1",
                    "Booking#b1:DEPOSIT-DEPOSIT_PAID>BALANCE@2"), described(outcome.records()));
            assertEquals(List.of("Payment#d1:AUTHORIZING-CAPTURE>CAPTURED@1"), billing.paymentActions.calls);
            assertEquals(List.of("Booking#b2:REQUESTED-CANCEL>CANCELLED@0",
                    "Booking#b1:DEPOSIT-DEPOSIT_PAID>BALANCE@2"), billing.bookingActions.calls);
            assertEquals(BookingStatus.BALANCE, owner.status());
        }
    }

    /**
     * The same direct exit under a maxCascadeDepth of 1: its depth, 2, exceeds the bound, so the call aborts with the
     * capture that ended the deposit, the exit's action never runs, and nothing is written.
     */
    @Test
    void aDirectExitBelowACascadedChildExceedsTheCascadeDepth() {
        Payment deposit = new Payment("d1", PaymentStatus.AUTHORIZING);
        Booking owner = new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(deposit)
                .attachBalance(new Payment("d2", PaymentStatus.AUTHORIZING));
        Booking booking = new Booking("b2", BookingStatus.REQUESTED);
        billing.bookingActions.behaviour = cancellingCaptures(deposit);
        try (MachineRegistry registry = MachineRegistry.builder().maxCascadeDepth(1).add(billing.bookingMachine())
                .add(billing.paymentMachine()).build()) {
            StateMachine<Booking, BookingStatus, BookingTransition> bookings = registry.of(BOOKINGS);
            CascadeDepthExceededException error = assertThrows(CascadeDepthExceededException.class,
                    () -> bookings.execute(booking, BookingTransition.CANCEL));
            assertEquals(1, error.maxDepth());
            assertEquals("transition aborted in Payment/PaymentStatus AUTHORIZING -CAPTURE-> CAPTURED: the cascade"
                    + " exceeds maxCascadeDepth = 1 [route Booking/BookingStatus#b2]", error.getMessage());
            assertEquals(List.of("Booking#b2:REQUESTED-CANCEL>CANCELLED@0"), billing.bookingActions.calls);
            assertEquals(PaymentStatus.AUTHORIZING, deposit.status());
            assertEquals(BookingStatus.DEPOSIT, owner.status());
            assertEquals(BookingStatus.REQUESTED, booking.status());
        }
    }

    /**
     * A deferred exit runs once the step that entered the node is over, one level below that step, whatever the depth
     * of the child's step that ended the child: BOOK, at depth 0, captures the deposit at depth 1 while the booking is
     * in flight, and the exit DEPOSIT_PAID then runs at depth 1 as well, so a maxCascadeDepth of 1 admits it and the
     * call commits. The contexts of both actions report depth 1.
     */
    @Test
    void aDeferredExitRunsOneLevelBelowTheStepThatEnteredTheNode() {
        Payment deposit = new Payment("d1", PaymentStatus.VOIDED);
        Booking booking = new Booking("b1", BookingStatus.REQUESTED).attachDeposit(deposit)
                .attachBalance(new Payment("d2", PaymentStatus.AUTHORIZING));
        billing.bookingActions.behaviour = (TransitionContext<Booking, BookingStatus, BookingTransition> ctx) -> {
            if (ctx.transition() == BookingTransition.BOOK) {
                ctx.cascade(PAYMENTS, deposit, PaymentTransition.CAPTURE);
            }
            return ActionOutcome.ok();
        };
        try (MachineRegistry registry = MachineRegistry.builder().maxCascadeDepth(1).add(billing.bookingMachine())
                .add(billing.paymentMachine()).build()) {
            Outcome<Booking, BookingStatus, BookingTransition> outcome =
                    registry.of(BOOKINGS).execute(booking, BookingTransition.BOOK);
            assertEquals(OutcomeCode.APPLIED, outcome.code());
            assertEquals(List.of("Booking#b1:REQUESTED-BOOK>DEPOSIT@0", "Payment#d1:AUTHORIZING-CAPTURE>CAPTURED@1",
                    "Booking#b1:DEPOSIT-DEPOSIT_PAID>BALANCE@1"), described(outcome.records()));
            assertEquals(List.of("Payment#d1:AUTHORIZING-CAPTURE>CAPTURED@1"), billing.paymentActions.calls);
            assertEquals(List.of("Booking#b1:REQUESTED-BOOK>DEPOSIT@0", "Booking#b1:DEPOSIT-DEPOSIT_PAID>BALANCE@1"),
                    billing.bookingActions.calls);
            assertEquals(BookingStatus.BALANCE, booking.status());
            assertEquals(PaymentStatus.CAPTURED, deposit.status());
        }
    }

    /** The receipt's initial state is terminal: issuing the ticket starts it and exits at once, in one commit. */
    @Test
    void aChildWhoseInitialStateIsTerminalEntersAndExitsInOneCommit() {
        GraphDefinition<Ticket, ReceiptStatus, ReceiptTransition> receipts = StateGraph.define(RECEIPTS)
                .initial(ReceiptStatus.SENT).terminal(ReceiptStatus.SENT).build();
        GraphDefinition<Ticket, TicketStatus, TicketTransition> tickets = StateGraph.define(TICKETS)
                .initial(TicketStatus.OPEN).terminal(TicketStatus.DONE)
                .statePolicy(TicketStatus.OPEN, "a ticket is issued")
                .statePolicy(TicketStatus.ISSUING, "a ticket finishes once its receipt is sent")
                .transition(TicketTransition.ISSUE, TicketStatus.OPEN, TicketStatus.ISSUING).endTransition()
                .transition(TicketTransition.FINISH, TicketStatus.ISSUING, TicketStatus.DONE).endTransition()
                .submachine(TicketStatus.ISSUING, receipts)
                    .exit(ReceiptStatus.SENT, TicketTransition.FINISH)
                    .endSubmachine()
                .build();
        Ticket ticket = new Ticket("t1");
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(tickets).state(Ticket::status, Ticket::setStatus).identity(Ticket::id))
                .add(StateMachines.from(receipts).state(Ticket::receipt, Ticket::setReceipt).identity(Ticket::id))
                .build()) {
            Outcome<Ticket, TicketStatus, TicketTransition> outcome =
                    registry.of(TICKETS).execute(ticket, TicketTransition.ISSUE);
            assertEquals(TicketStatus.ISSUING, outcome.recordedState());
            assertEquals(List.of("Ticket#t1:OPEN-ISSUE>ISSUING@0", "Ticket#t1:ISSUING-FINISH>DONE@1"),
                    described(outcome.records()));
            assertEquals(List.of(new SubmachineStart<>(RECEIPTS, ticket, ReceiptStatus.SENT, ReceiptStatus.SENT,
                    TICKETS, ticket, TicketStatus.ISSUING, 0)), outcome.starts());
            assertEquals(TicketStatus.DONE, ticket.status());
        }
    }

    /**
     * Review Focus #4: COLLECT over the deposit and the balance of one booking. The first capture's exit moves the
     * booking to BALANCE, which starts the balance payment and holds it; the second capture then exits BALANCE. Both
     * exits are in the records, in log order.
     */
    @Test
    void collectingOverTwoChildrenOfOneParentRunsBothExits() {
        Payment deposit = new Payment("d1", PaymentStatus.AUTHORIZING);
        Payment balance = new Payment("d2", PaymentStatus.AUTHORIZING);
        Booking owner = new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(deposit).attachBalance(balance);
        Booking booking = new Booking("b2", BookingStatus.REQUESTED);
        List<List<Outcome<Payment, PaymentStatus, PaymentTransition>>> collected = new ArrayList<>();
        billing.bookingActions.behaviour = (TransitionContext<Booking, BookingStatus, BookingTransition> ctx) -> {
            if (ctx.entity() == booking) {
                collected.add(ctx.cascadeAll(PAYMENTS, List.of(deposit, balance), PaymentTransition.CAPTURE,
                        CascadePolicy.COLLECT));
            }
            return ActionOutcome.ok();
        };
        try (MachineRegistry registry = billing.registry()) {
            Outcome<Booking, BookingStatus, BookingTransition> outcome =
                    registry.of(BOOKINGS).execute(booking, BookingTransition.CANCEL);
            assertEquals(2, single(collected).size());
            assertEquals(List.of("Booking#b2:REQUESTED-CANCEL>CANCELLED@0", "Payment#d1:AUTHORIZING-CAPTURE>CAPTURED@1",
                    "Booking#b1:DEPOSIT-DEPOSIT_PAID>BALANCE@2", "Payment#d2:AUTHORIZING-CAPTURE>CAPTURED@1",
                    "Booking#b1:BALANCE-BALANCE_PAID>CONFIRMED@2"), described(outcome.records()));
            assertEquals(List.of(balanceStart(balance, owner, 2)), outcome.starts());
            assertEquals(BookingStatus.CONFIRMED, owner.status());
            assertEquals(PaymentStatus.CAPTURED, deposit.status());
            assertEquals(PaymentStatus.CAPTURED, balance.status());
        }
    }

    /**
     * Review Focus #5: the hook of the exit hop throws. Every state is written, and the exception names the exit hop
     * and carries every hop and every start of the call.
     */
    @Test
    void aFailingHookOfAnExitReportsTheExitAndTheStartsOfTheCall() {
        Payment deposit = new Payment("d1", PaymentStatus.AUTHORIZING);
        Payment balance = new Payment("d2", PaymentStatus.AUTHORIZING);
        Booking booking = new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(deposit).attachBalance(balance);
        IllegalStateException down = new IllegalStateException("the audit is down");
        billing.afterHook = (String hop) -> {
            if (hop.startsWith("Booking#b1")) {
                throw down;
            }
        };
        try (MachineRegistry registry = billing.registry()) {
            StateMachine<Payment, PaymentStatus, PaymentTransition> payments = registry.of(PAYMENTS);
            AfterCommitException error = assertThrows(AfterCommitException.class,
                    () -> payments.execute(deposit, PaymentTransition.CAPTURE));
            assertSame(down, error.getCause());
            assertEquals(StatesWritten.ALL, error.statesWritten());
            assertEquals(List.of("Booking#b1:DEPOSIT-DEPOSIT_PAID>BALANCE@1"), described(List.of(error.hop())));
            assertEquals(List.of("Payment#d1:AUTHORIZING-CAPTURE>CAPTURED@0",
                    "Booking#b1:DEPOSIT-DEPOSIT_PAID>BALANCE@1"), described(error.records()));
            assertEquals(List.of(balanceStart(balance, booking, 1)), error.starts());
            assertEquals(PaymentStatus.CAPTURED, deposit.status());
            assertEquals(BookingStatus.BALANCE, booking.status());
            assertEquals(PaymentStatus.AUTHORIZING, balance.status());
        }
    }

    /** The billing machines, with the booking graph written by hand as {@link HandWritten} says. */
    private MachineRegistry handWritten(Optional<BookingTransition> voided) {
        return MachineRegistry.builder()
                .add(StateMachines.from(new HandWritten(billing.bookings, voided))
                        .state(Booking::status, Booking::setStatus).identity(Booking::id))
                .add(billing.paymentMachine()).build();
    }

    /** A hand-written graph whose binding maps no exit to a terminal: reaching it aborts the call (spec D3). */
    @Test
    void aTerminalWithNoExitAbortsTheCall() {
        Payment deposit = new Payment("d1", PaymentStatus.AUTHORIZING);
        Booking booking = new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(deposit);
        try (MachineRegistry registry = handWritten(Optional.empty())) {
            StateMachine<Payment, PaymentStatus, PaymentTransition> payments = registry.of(PAYMENTS);
            TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                    () -> payments.execute(deposit, PaymentTransition.VOID));
            assertEquals(CallPhase.ACTION, error.phase());
            assertEquals("transition aborted in Payment/PaymentStatus AUTHORIZING -VOID-> VOIDED: the terminal VOIDED"
                    + " of Payment/PaymentStatus has no exit in DEPOSIT", error.getMessage());
            assertEquals(PaymentStatus.AUTHORIZING, deposit.status());
            assertEquals(BookingStatus.DEPOSIT, booking.status());
        }
    }

    /** A hand-written graph whose exit has no edge from the node: the exit is rejected and the call aborts. */
    @Test
    void anExitWithNoEdgeFromTheNodeAbortsTheCall() {
        Payment deposit = new Payment("d1", PaymentStatus.AUTHORIZING);
        Booking booking = new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(deposit);
        try (MachineRegistry registry = handWritten(Optional.of(BookingTransition.BALANCE_PAID))) {
            StateMachine<Payment, PaymentStatus, PaymentTransition> payments = registry.of(PAYMENTS);
            TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                    () -> payments.execute(deposit, PaymentTransition.VOID));
            assertEquals(CallPhase.ACTION, error.phase());
            assertEquals("transition aborted in Booking/BookingStatus DEPOSIT -BALANCE_PAID-> ?: rejected inside the"
                    + " exit of DEPOSIT by no such transition: a deposit is paid, restarted or cancelled",
                    error.getMessage());
            assertEquals(PaymentStatus.AUTHORIZING, deposit.status());
            assertEquals(BookingStatus.DEPOSIT, booking.status());
        }
    }

    /**
     * The exit runs once the child's step is no longer in flight, so a cascade from the exit's action back to the
     * child finds it in its terminal state, where no edge leaves: the cascade is rejected and the call aborts.
     */
    @Test
    void anExitActionThatCascadesIntoItsEndedChildAbortsTheCall() {
        Payment deposit = new Payment("d1", PaymentStatus.AUTHORIZING);
        Booking booking = new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(deposit)
                .attachBalance(new Payment("d2", PaymentStatus.AUTHORIZING));
        billing.bookingActions.behaviour = (TransitionContext<Booking, BookingStatus, BookingTransition> ctx) -> {
            ctx.cascade(PAYMENTS, deposit, PaymentTransition.HOLD);
            return ActionOutcome.ok();
        };
        try (MachineRegistry registry = billing.registry()) {
            StateMachine<Payment, PaymentStatus, PaymentTransition> payments = registry.of(PAYMENTS);
            TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                    () -> payments.execute(deposit, PaymentTransition.CAPTURE));
            assertEquals(CallPhase.ACTION, error.phase());
            assertEquals("transition aborted in Payment/PaymentStatus CAPTURED -HOLD-> ?: rejected inside a cascade by"
                    + " no such transition: terminal state CAPTURED accepts no transitions"
                    + " [route Booking/BookingStatus#b1]", error.getMessage());
            assertEquals(PaymentStatus.AUTHORIZING, deposit.status());
            assertEquals(BookingStatus.DEPOSIT, booking.status());
        }
    }

    /** The states of the outer machine in the recursive terminal-initial regression. */
    enum ParentState { OPEN, BOUND, DONE }

    /** Identifiers of the outer machine in the recursive terminal-initial regression. */
    enum ParentId { ENTER, FINISH }

    /** The intermediate machine starts in a node bound to the leaf. */
    enum ChildState { WAIT, DONE }

    /** The intermediate machine's exit selected by its completed leaf. */
    enum ChildId { FINISH }

    /** The leaf's initial state is terminal. */
    enum LeafState { DONE }

    /** The leaf declares no outgoing edge. */
    enum LeafId { UNUSED }

    /** One entity with three independently governed states. */
    static final class Nested {
        private ParentState parent = ParentState.OPEN;
        private ChildState child = ChildState.WAIT;
        private LeafState leaf = LeafState.DONE;

        @Override
        public String toString() { return "Nested#one"; }
    }

    /** Recursive starts complete the terminal leaf first, then both containing machines in one commit. */
    @Test
    void aRecursivelyStartedTerminalLeafExitsEveryParentInTheSameCommit() {
        MachineKey<Nested, ParentState, ParentId> parents =
                MachineKey.of(Nested.class, ParentState.class, ParentId.class);
        MachineKey<Nested, ChildState, ChildId> children =
                MachineKey.of(Nested.class, ChildState.class, ChildId.class);
        MachineKey<Nested, LeafState, LeafId> leaves =
                MachineKey.of(Nested.class, LeafState.class, LeafId.class);
        GraphDefinition<Nested, LeafState, LeafId> leafGraph = StateGraph.define(leaves)
                .initial(LeafState.DONE).terminal(LeafState.DONE).build();
        GraphDefinition<Nested, ChildState, ChildId> childGraph = StateGraph.define(children)
                .initial(ChildState.WAIT).terminal(ChildState.DONE)
                .statePolicy(ChildState.WAIT, "wait for the leaf")
                .transition(ChildId.FINISH, ChildState.WAIT, ChildState.DONE).endTransition()
                .submachine(ChildState.WAIT, leafGraph).exit(LeafState.DONE, ChildId.FINISH).endSubmachine().build();
        GraphDefinition<Nested, ParentState, ParentId> parentGraph = StateGraph.define(parents)
                .initial(ParentState.OPEN).terminal(ParentState.DONE)
                .statePolicy(ParentState.OPEN, "enter the child")
                .statePolicy(ParentState.BOUND, "wait for the child")
                .transition(ParentId.ENTER, ParentState.OPEN, ParentState.BOUND).endTransition()
                .transition(ParentId.FINISH, ParentState.BOUND, ParentState.DONE).endTransition()
                .submachine(ParentState.BOUND, childGraph).exit(ChildState.DONE, ParentId.FINISH)
                .endSubmachine().build();
        Nested entity = new Nested();
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(parentGraph).state((Nested value) -> value.parent,
                        (Nested value, ParentState state) -> value.parent = state).identity((Nested value) -> "one"))
                .add(StateMachines.from(childGraph).state((Nested value) -> value.child,
                        (Nested value, ChildState state) -> value.child = state).identity((Nested value) -> "one"))
                .add(StateMachines.from(leafGraph).state((Nested value) -> value.leaf,
                        (Nested value, LeafState state) -> value.leaf = state).identity((Nested value) -> "one"))
                .build()) {
            Outcome<Nested, ParentState, ParentId> outcome = registry.of(parents).execute(entity, ParentId.ENTER);
            assertEquals(ParentState.DONE, entity.parent);
            assertEquals(ChildState.DONE, entity.child);
            assertEquals(LeafState.DONE, entity.leaf);
            assertEquals(ParentState.BOUND, outcome.recordedState());
            assertEquals(List.of("Nested#one:OPEN-ENTER>BOUND@0", "Nested#one:WAIT-FINISH>DONE@1",
                    "Nested#one:BOUND-FINISH>DONE@2"), described(outcome.records()));
            assertEquals(List.of(new SubmachineStart<>(children, entity, ChildState.WAIT, ChildState.WAIT,
                    parents, entity, ParentState.BOUND, 0), new SubmachineStart<>(leaves, entity, LeafState.DONE,
                    LeafState.DONE, children, entity, ChildState.WAIT, 0)), outcome.starts());
        }
    }
}
