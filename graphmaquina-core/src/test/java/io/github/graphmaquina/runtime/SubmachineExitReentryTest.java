package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.Billing.BOOKINGS;
import static io.github.graphmaquina.fixtures.Billing.PAYMENTS;
import static io.github.graphmaquina.fixtures.RecordTexts.described;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.CommitContext;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.OutcomeCode;
import io.github.graphmaquina.api.StatesWritten;
import io.github.graphmaquina.api.SubmachineStart;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.WatchCondition;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.error.CommitFailedException;
import io.github.graphmaquina.fixtures.Billing;
import io.github.graphmaquina.fixtures.Billing.Booking;
import io.github.graphmaquina.fixtures.Billing.BookingStatus;
import io.github.graphmaquina.fixtures.Billing.BookingTransition;
import io.github.graphmaquina.fixtures.Billing.Payment;
import io.github.graphmaquina.fixtures.Billing.PaymentStatus;
import io.github.graphmaquina.fixtures.Billing.PaymentTransition;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * The atomic exit of {@link SubmachineExitTest} when the parent's node changes under the child. A parent that leaves
 * the node by its own edge, or that the child's action moves away, runs no exit; a parent that enters the node again
 * with another child cancels the exit of the child it replaced, and the child that the latest entry started exits it
 * once; a child replaced without a re-entry still exits its parent. The same holds for the exits that an entering step
 * defers, for the exits of descendants, and for a child whose completion arrives after its parent entered a new bound
 * node.
 */
class SubmachineExitReentryTest {

    private final Billing billing = new Billing();

    /**
     * The booking cancels from DEPOSIT, and its action voids the deposit: the binding still holds the payment, but
     * the booking is in flight, so the void runs no exit, and the payment keeps the state it reached.
     */
    @Test
    void aParentThatLeavesTheNodeByItsOwnEdgeRunsNoExit() {
        Payment deposit = new Payment("d1", PaymentStatus.AUTHORIZING);
        Booking booking = new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(deposit);
        billing.bookingActions.behaviour = (TransitionContext<Booking, BookingStatus, BookingTransition> ctx) -> {
            ctx.cascade(PAYMENTS, deposit, PaymentTransition.VOID);
            return ActionOutcome.ok();
        };
        try (MachineRegistry registry = billing.registry()) {
            Outcome<Booking, BookingStatus, BookingTransition> outcome =
                    registry.of(BOOKINGS).execute(booking, BookingTransition.CANCEL);
            assertEquals(List.of("Booking#b1:DEPOSIT-CANCEL>CANCELLED@0", "Payment#d1:AUTHORIZING-VOID>VOIDED@1"),
                    described(outcome.records()));
            assertEquals(BookingStatus.CANCELLED, booking.status());
            assertEquals(PaymentStatus.VOIDED, deposit.status());
        }
    }

    /** The capture's own action cancels the booking: the booking is no longer in DEPOSIT, so no exit runs. */
    @Test
    void aChildWhoseActionMovedItsParentAwayRunsNoExit() {
        Payment deposit = new Payment("d1", PaymentStatus.AUTHORIZING);
        Booking booking = new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(deposit);
        billing.paymentActions.behaviour = (TransitionContext<Payment, PaymentStatus, PaymentTransition> ctx) -> {
            ctx.cascade(BOOKINGS, booking, BookingTransition.CANCEL);
            return ActionOutcome.ok();
        };
        try (MachineRegistry registry = billing.registry()) {
            Outcome<Payment, PaymentStatus, PaymentTransition> outcome =
                    registry.of(PAYMENTS).execute(deposit, PaymentTransition.CAPTURE);
            assertEquals(List.of("Payment#d1:AUTHORIZING-CAPTURE>CAPTURED@0", "Booking#b1:DEPOSIT-CANCEL>CANCELLED@1"),
                    described(outcome.records()));
            assertEquals(BookingStatus.CANCELLED, booking.status());
            assertEquals(PaymentStatus.CAPTURED, deposit.status());
        }
    }

    /**
     * The capture's own action makes another payment the booking's deposit. The binding held the captured payment when
     * its step began, and its parent is still in DEPOSIT, so the exit runs: the binding is not judged again.
     */
    @Test
    void aChildReplacedDuringItsActionStillExitsItsParent() {
        Payment deposit = new Payment("d1", PaymentStatus.AUTHORIZING);
        Payment replacement = new Payment("d3", PaymentStatus.AUTHORIZING);
        Booking booking = new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(deposit)
                .attachBalance(new Payment("d2", PaymentStatus.AUTHORIZING));
        billing.paymentActions.behaviour = (TransitionContext<Payment, PaymentStatus, PaymentTransition> ctx) -> {
            booking.attachDeposit(replacement);
            return ActionOutcome.ok();
        };
        try (MachineRegistry registry = billing.registry()) {
            Outcome<Payment, PaymentStatus, PaymentTransition> outcome =
                    registry.of(PAYMENTS).execute(deposit, PaymentTransition.CAPTURE);
            assertEquals(List.of("Payment#d1:AUTHORIZING-CAPTURE>CAPTURED@0",
                    "Booking#b1:DEPOSIT-DEPOSIT_PAID>BALANCE@1"), described(outcome.records()));
            assertEquals(BookingStatus.BALANCE, booking.status());
            assertEquals(PaymentStatus.CAPTURED, deposit.status());
            assertEquals(PaymentStatus.AUTHORIZING, replacement.status());
        }
    }

    /**
     * The capture's own action makes another payment the deposit and restarts the booking in DEPOSIT, which starts that
     * payment. The capture ends a payment of the activation the restart replaced, so no exit runs: the booking stays in
     * DEPOSIT with its new payment started.
     */
    @Test
    void aChildWhoseParentReenteredTheNodeWithAnotherChildRunsNoExit() {
        Payment deposit = new Payment("d1", PaymentStatus.AUTHORIZING);
        Payment replacement = new Payment("d3", PaymentStatus.HELD);
        Booking booking = new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(deposit)
                .attachBalance(new Payment("d2", PaymentStatus.AUTHORIZING));
        billing.paymentActions.behaviour = (TransitionContext<Payment, PaymentStatus, PaymentTransition> ctx) -> {
            booking.attachDeposit(replacement);
            ctx.cascade(BOOKINGS, booking, BookingTransition.RESTART);
            return ActionOutcome.ok();
        };
        try (MachineRegistry registry = billing.registry()) {
            Outcome<Payment, PaymentStatus, PaymentTransition> outcome =
                    registry.of(PAYMENTS).execute(deposit, PaymentTransition.CAPTURE);
            assertEquals(List.of("Payment#d1:AUTHORIZING-CAPTURE>CAPTURED@0", "Booking#b1:DEPOSIT-RESTART>DEPOSIT@1"),
                    described(outcome.records()));
            assertEquals(List.of(new SubmachineStart<>(PAYMENTS, replacement, PaymentStatus.HELD,
                    PaymentStatus.AUTHORIZING, BOOKINGS, booking, BookingStatus.DEPOSIT, 1)), outcome.starts());
            assertEquals(BookingStatus.DEPOSIT, booking.status());
            assertEquals(PaymentStatus.CAPTURED, deposit.status());
            assertEquals(PaymentStatus.AUTHORIZING, replacement.status());
        }
    }

    /**
     * After the restart starts the new deposit, the old capture's action captures that one too. The new deposit's end
     * exits the booking from the activation that started it, and the old capture's end then finds the booking gone
     * from DEPOSIT: one exit, not two.
     */
    @Test
    void theNewChildOfAReenteredNodeExitsItOnce() {
        Payment deposit = new Payment("d1", PaymentStatus.AUTHORIZING);
        Payment replacement = new Payment("d3", PaymentStatus.HELD);
        Booking booking = new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(deposit)
                .attachBalance(new Payment("d2", PaymentStatus.AUTHORIZING));
        billing.paymentActions.behaviour = (TransitionContext<Payment, PaymentStatus, PaymentTransition> ctx) -> {
            if (ctx.entity() == deposit) {
                booking.attachDeposit(replacement);
                ctx.cascade(BOOKINGS, booking, BookingTransition.RESTART);
                ctx.cascade(PAYMENTS, replacement, PaymentTransition.CAPTURE);
            }
            return ActionOutcome.ok();
        };
        try (MachineRegistry registry = billing.registry()) {
            Outcome<Payment, PaymentStatus, PaymentTransition> outcome =
                    registry.of(PAYMENTS).execute(deposit, PaymentTransition.CAPTURE);
            assertEquals(List.of("Payment#d1:AUTHORIZING-CAPTURE>CAPTURED@0", "Booking#b1:DEPOSIT-RESTART>DEPOSIT@1",
                    "Payment#d3:AUTHORIZING-CAPTURE>CAPTURED@1", "Booking#b1:DEPOSIT-DEPOSIT_PAID>BALANCE@2"),
                    described(outcome.records()));
            assertEquals(BookingStatus.BALANCE, booking.status());
            assertEquals(PaymentStatus.CAPTURED, deposit.status());
            assertEquals(PaymentStatus.CAPTURED, replacement.status());
        }
    }

    /**
     * A free payment's action restarts the booking in DEPOSIT, which starts its deposit again, and then captures that
     * deposit. The restart came before the capture's binding check, so the capture's step entered the node again with
     * no other child, and its exit runs.
     */
    @Test
    void aChildStartedByTheLatestEntryOfItsNodeExitsIt() {
        Payment deposit = new Payment("d1", PaymentStatus.AUTHORIZING);
        Payment free = new Payment("f1", PaymentStatus.AUTHORIZING);
        Booking booking = new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(deposit)
                .attachBalance(new Payment("d2", PaymentStatus.AUTHORIZING));
        billing.paymentActions.behaviour = (TransitionContext<Payment, PaymentStatus, PaymentTransition> ctx) -> {
            if (ctx.entity() == free) {
                ctx.cascade(BOOKINGS, booking, BookingTransition.RESTART);
                ctx.cascade(PAYMENTS, deposit, PaymentTransition.CAPTURE);
            }
            return ActionOutcome.ok();
        };
        try (MachineRegistry registry = billing.registry()) {
            Outcome<Payment, PaymentStatus, PaymentTransition> outcome =
                    registry.of(PAYMENTS).execute(free, PaymentTransition.CAPTURE);
            assertEquals(List.of("Payment#f1:AUTHORIZING-CAPTURE>CAPTURED@0", "Booking#b1:DEPOSIT-RESTART>DEPOSIT@1",
                    "Payment#d1:AUTHORIZING-CAPTURE>CAPTURED@1", "Booking#b1:DEPOSIT-DEPOSIT_PAID>BALANCE@2"),
                    described(outcome.records()));
            assertEquals(BookingStatus.BALANCE, booking.status());
            assertEquals(PaymentStatus.CAPTURED, deposit.status());
        }
    }

    /**
     * A free payment's action restarts the booking in DEPOSIT, then makes another payment its deposit and captures it.
     * The restart came before the new deposit's step began, which found the new deposit held, so its exit runs.
     */
    @Test
    void aChildHeldAfterAnEarlierReentryOfItsNodeExitsIt() {
        Payment deposit = new Payment("d1", PaymentStatus.AUTHORIZING);
        Payment replacement = new Payment("d3", PaymentStatus.AUTHORIZING);
        Payment free = new Payment("f1", PaymentStatus.AUTHORIZING);
        Booking booking = new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(deposit)
                .attachBalance(new Payment("d2", PaymentStatus.AUTHORIZING));
        billing.paymentActions.behaviour = (TransitionContext<Payment, PaymentStatus, PaymentTransition> ctx) -> {
            if (ctx.entity() == free) {
                ctx.cascade(BOOKINGS, booking, BookingTransition.RESTART);
                booking.attachDeposit(replacement);
                ctx.cascade(PAYMENTS, replacement, PaymentTransition.CAPTURE);
            }
            return ActionOutcome.ok();
        };
        try (MachineRegistry registry = billing.registry()) {
            Outcome<Payment, PaymentStatus, PaymentTransition> outcome =
                    registry.of(PAYMENTS).execute(free, PaymentTransition.CAPTURE);
            assertEquals(List.of("Payment#f1:AUTHORIZING-CAPTURE>CAPTURED@0", "Booking#b1:DEPOSIT-RESTART>DEPOSIT@1",
                    "Payment#d3:AUTHORIZING-CAPTURE>CAPTURED@1", "Booking#b1:DEPOSIT-DEPOSIT_PAID>BALANCE@2"),
                    described(outcome.records()));
            assertEquals(BookingStatus.BALANCE, booking.status());
            assertEquals(PaymentStatus.CAPTURED, replacement.status());
        }
    }

    /**
     * A free payment restarts the booking in DEPOSIT through another free payment, starting its deposit d1 at depth 2,
     * then makes d3 the deposit and captures it. d3's own action makes d1 the deposit again and restarts the booking,
     * which logs a start of d1 equal in every field to the earlier one. That start is still a later entry of the node
     * with another child, so d3's end runs no exit.
     */
    @Test
    void aReentryLoggingAStartEqualToAnEarlierOneStillCancelsTheExit() {
        Payment deposit = new Payment("d1", PaymentStatus.AUTHORIZING);
        Payment replacement = new Payment("d3", PaymentStatus.AUTHORIZING);
        Payment first = new Payment("f1", PaymentStatus.AUTHORIZING);
        Payment second = new Payment("f2", PaymentStatus.AUTHORIZING);
        Booking booking = new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(deposit)
                .attachBalance(new Payment("d2", PaymentStatus.AUTHORIZING));
        billing.paymentActions.behaviour = (TransitionContext<Payment, PaymentStatus, PaymentTransition> ctx) -> {
            if (ctx.entity() == first) {
                ctx.cascade(PAYMENTS, second, PaymentTransition.CAPTURE);
                booking.attachDeposit(replacement);
                ctx.cascade(PAYMENTS, replacement, PaymentTransition.CAPTURE);
            } else if (ctx.entity() == second) {
                ctx.cascade(BOOKINGS, booking, BookingTransition.RESTART);
            } else if (ctx.entity() == replacement) {
                booking.attachDeposit(deposit);
                ctx.cascade(BOOKINGS, booking, BookingTransition.RESTART);
            }
            return ActionOutcome.ok();
        };
        try (MachineRegistry registry = billing.registry()) {
            Outcome<Payment, PaymentStatus, PaymentTransition> outcome =
                    registry.of(PAYMENTS).execute(first, PaymentTransition.CAPTURE);
            assertEquals(List.of("Payment#f1:AUTHORIZING-CAPTURE>CAPTURED@0",
                    "Payment#f2:AUTHORIZING-CAPTURE>CAPTURED@1", "Booking#b1:DEPOSIT-RESTART>DEPOSIT@2",
                    "Payment#d3:AUTHORIZING-CAPTURE>CAPTURED@1", "Booking#b1:DEPOSIT-RESTART>DEPOSIT@2"),
                    described(outcome.records()));
            assertEquals(BookingStatus.DEPOSIT, booking.status());
            assertEquals(PaymentStatus.AUTHORIZING, deposit.status());
        }
    }

    /**
     * No child's lookup of its parent finds one here, so a child's own end never runs its parent's exit: only the exit
     * that an entering step defers can. START's action moves the token, whose action opens the office, which starts
     * the desk in SERVING, the token in QUEUED and its stamp in BLANK; START's action then stamps the stamp. START's
     * step ends with the desk in SERVING, so it runs the token's deferred exit SERVE, whose action renews SERVING: the
     * same token and its stamp start again, the stamp is stamped again, and the token ends SERVED a second time. The
     * renewal entered SERVING with the same token, so the desk's deferred exit still runs, once.
     */
    @Test
    void aReentryWithTheSameChildDuringADeferredExitStillExitsTheParent() {
        Stamp stamp = new Stamp("s1");
        Token token = new Token("t1", stamp);
        Desk desk = new Desk(token);
        Office office = new Office(desk);
        Outcome<Desk, DeskState, DeskId> outcome = startTheDesk(desk, office,
                (TransitionContext<Token, TokenState, TokenId> ctx) -> {
                    ctx.cascade(DESKS, desk, DeskId.RENEW);
                    ctx.cascade(STAMPS, stamp, StampId.STAMP);
                });
        assertEquals(OutcomeCode.APPLIED, outcome.code());
        assertEquals(List.of("Desk:NEW-START>WAITING@0", "Token:CALLED-MOVE>SERVED@1",
                "Office:IDLE-OPEN_UP>OPEN@2", "Stamp:BLANK-STAMP>STAMPED@1", "Token:QUEUED-SERVE>SERVED@1",
                "Desk:SERVING-RENEW>SERVING@2", "Stamp:BLANK-STAMP>STAMPED@2", "Token:QUEUED-SERVE>SERVED@2",
                "Desk:SERVING-FINISH>DONE@1"), described(outcome.records()));
        assertEquals(DeskState.DONE, desk.state);
        assertEquals(TokenState.SERVED, token.state);
        assertEquals(OfficeState.OPEN, office.state);
    }

    /**
     * The same scenario, with the renewal made through a relay, one level deeper: its start of the same token differs
     * from the first one only in depth. The latest start since the selection still names the same token, which ends
     * SERVED, so the desk's deferred exit runs, once.
     */
    @Test
    void aReentryWithTheSameChildAtAnotherDepthStillExitsTheParent() {
        Stamp stamp = new Stamp("s1");
        Token token = new Token("t1", stamp);
        Desk desk = new Desk(token);
        Office office = new Office(desk);
        Relay relay = new Relay();
        Outcome<Desk, DeskState, DeskId> outcome = startTheDesk(desk, office,
                (TransitionContext<Token, TokenState, TokenId> ctx) -> {
                    ctx.cascade(RELAYS, relay, StampId.STAMP);
                    ctx.cascade(STAMPS, stamp, StampId.STAMP);
                });
        assertEquals(OutcomeCode.APPLIED, outcome.code());
        assertEquals(List.of("Desk:NEW-START>WAITING@0", "Token:CALLED-MOVE>SERVED@1",
                "Office:IDLE-OPEN_UP>OPEN@2", "Stamp:BLANK-STAMP>STAMPED@1", "Token:QUEUED-SERVE>SERVED@1",
                "Relay:BLANK-STAMP>STAMPED@2", "Desk:SERVING-RENEW>SERVING@3", "Stamp:BLANK-STAMP>STAMPED@2",
                "Token:QUEUED-SERVE>SERVED@2", "Desk:SERVING-FINISH>DONE@1"), described(outcome.records()));
        assertEquals(DeskState.DONE, desk.state);
        assertEquals(TokenState.SERVED, token.state);
    }

    /**
     * The same scenario, where the first SERVE renews SERVING with another token and then again with the first one:
     * the latest start since the deferred exit selected its start names the first token again, which ends SERVED, so
     * the desk's deferred exit runs, once. The other token stays started in QUEUED.
     */
    @Test
    void aReentryWithAnotherChildThenTheSameOneStillExitsTheParent() {
        Stamp stamp = new Stamp("s1");
        Token token = new Token("t1", stamp);
        Token other = new Token("t2", new Stamp("s2"));
        Desk desk = new Desk(token);
        Office office = new Office(desk);
        Outcome<Desk, DeskState, DeskId> outcome = startTheDesk(desk, office,
                (TransitionContext<Token, TokenState, TokenId> ctx) -> {
                    desk.token = other;
                    ctx.cascade(DESKS, desk, DeskId.RENEW);
                    desk.token = token;
                    ctx.cascade(DESKS, desk, DeskId.RENEW);
                    ctx.cascade(STAMPS, stamp, StampId.STAMP);
                });
        assertEquals(OutcomeCode.APPLIED, outcome.code());
        assertEquals(List.of("Desk:NEW-START>WAITING@0", "Token:CALLED-MOVE>SERVED@1",
                "Office:IDLE-OPEN_UP>OPEN@2", "Stamp:BLANK-STAMP>STAMPED@1", "Token:QUEUED-SERVE>SERVED@1",
                "Desk:SERVING-RENEW>SERVING@2", "Desk:SERVING-RENEW>SERVING@2", "Stamp:BLANK-STAMP>STAMPED@2",
                "Token:QUEUED-SERVE>SERVED@2", "Desk:SERVING-FINISH>DONE@1"), described(outcome.records()));
        assertEquals(DeskState.DONE, desk.state);
        assertEquals(TokenState.SERVED, token.state);
        assertEquals(TokenState.QUEUED, other.state);
    }

    /**
     * Runs START on the desk of the re-entry scenarios. START moves the desk's token and stamps its stamp; the first
     * SERVE of any token runs {@code firstServe}.
     */
    private static Outcome<Desk, DeskState, DeskId> startTheDesk(Desk desk, Office office,
            Consumer<TransitionContext<Token, TokenState, TokenId>> firstServe) {
        Token token = desk.token;
        GraphDefinition<Stamp, StampState, StampId> stamps = stampGraph();
        GraphDefinition<Token, TokenState, TokenId> tokens = tokenGraph(stamps, office, firstServe);
        GraphDefinition<Desk, DeskState, DeskId> desks = deskGraph(tokens, token);
        GraphDefinition<Office, OfficeState, OfficeId> offices = officeGraph(desks);
        GraphDefinition<Relay, StampState, StampId> relays = relayGraph(desk);
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(relays).state((Relay value) -> value.state,
                        (Relay value, StampState state) -> value.state = state).identity((Relay value) -> "r"))
                .add(StateMachines.from(offices).state((Office value) -> value.state,
                        (Office value, OfficeState state) -> value.state = state).identity((Office value) -> "o"))
                .add(StateMachines.from(desks).state((Desk value) -> value.state,
                        (Desk value, DeskState state) -> value.state = state).identity((Desk value) -> "d"))
                .add(StateMachines.from(tokens).state((Token value) -> value.state,
                        (Token value, TokenState state) -> value.state = state).identity((Token value) -> value.name))
                .add(StateMachines.from(stamps).state((Stamp value) -> value.state,
                        (Stamp value, StampState state) -> value.state = state).identity((Stamp value) -> value.name))
                .build()) {
            return registry.of(DESKS).execute(desk, DeskId.START);
        }
    }

    /** A stamp that its only edge stamps. */
    private static GraphDefinition<Stamp, StampState, StampId> stampGraph() {
        return StateGraph.define(STAMPS)
                .initial(StampState.BLANK).terminal(StampState.STAMPED)
                .statePolicy(StampState.BLANK, "stamp it")
                .transition(StampId.STAMP, StampState.BLANK, StampState.STAMPED).endTransition()
                .build();
    }

    /**
     * A token whose QUEUED state holds its stamp, whose exit SERVE runs once the stamp is stamped, and whose MOVE
     * opens the office; the first SERVE of any token runs {@code firstServe}.
     */
    private static GraphDefinition<Token, TokenState, TokenId> tokenGraph(
            GraphDefinition<Stamp, StampState, StampId> stamps, Office office,
            Consumer<TransitionContext<Token, TokenState, TokenId>> firstServe) {
        return StateGraph.define(TOKENS)
                .initial(TokenState.QUEUED).terminal(TokenState.SERVED)
                .statePolicy(TokenState.QUEUED, "wait for the stamp")
                .statePolicy(TokenState.CALLED, "move")
                .transition(TokenId.CALL, TokenState.QUEUED, TokenState.CALLED).endTransition()
                .transition(TokenId.MOVE, TokenState.CALLED, TokenState.SERVED)
                    .action("Move", (TransitionContext<Token, TokenState, TokenId> ctx) -> {
                        ctx.cascade(OFFICES, office, OfficeId.OPEN_UP);
                        return ActionOutcome.ok();
                    }).endTransition()
                .transition(TokenId.SERVE, TokenState.QUEUED, TokenState.SERVED)
                    .action("Serve", (TransitionContext<Token, TokenState, TokenId> ctx) -> {
                        ctx.entity().serves++;
                        if (ctx.entity().serves == 1) {
                            firstServe.accept(ctx);
                        }
                        return ActionOutcome.ok();
                    }).endTransition()
                .submachine(TokenState.QUEUED, stamps, (Token value) -> Optional.of(value.stamp),
                        (Stamp value) -> Optional.empty())
                    .exit(StampState.STAMPED, TokenId.SERVE).endSubmachine()
                .build();
    }

    /** A desk whose SERVING state holds its token, whose START moves {@code token} and stamps its stamp. */
    private static GraphDefinition<Desk, DeskState, DeskId> deskGraph(
            GraphDefinition<Token, TokenState, TokenId> tokens, Token token) {
        return StateGraph.define(DESKS)
                .initial(DeskState.SERVING).terminal(DeskState.DONE).allowsCycle(DeskState.SERVING)
                .statePolicy(DeskState.NEW, "start")
                .statePolicy(DeskState.WAITING, "resume")
                .statePolicy(DeskState.SERVING, "wait for the token")
                .transition(DeskId.START, DeskState.NEW, DeskState.WAITING)
                    .action("Start", (TransitionContext<Desk, DeskState, DeskId> ctx) -> {
                        ctx.cascade(TOKENS, token, TokenId.MOVE);
                        ctx.cascade(STAMPS, token.stamp, StampId.STAMP);
                        return ActionOutcome.ok();
                    }).endTransition()
                .transition(DeskId.RESUME, DeskState.WAITING, DeskState.SERVING).endTransition()
                .transition(DeskId.BACK, DeskState.SERVING, DeskState.NEW).endTransition()
                .transition(DeskId.RENEW, DeskState.SERVING, DeskState.SERVING).endTransition()
                .transition(DeskId.FINISH, DeskState.SERVING, DeskState.DONE).endTransition()
                .submachine(DeskState.SERVING, tokens, (Desk value) -> Optional.of(value.token),
                        (Token value) -> Optional.empty())
                    .exit(TokenState.SERVED, DeskId.FINISH).endSubmachine()
                .build();
    }

    /** An office whose OPEN state holds its desk. */
    private static GraphDefinition<Office, OfficeState, OfficeId> officeGraph(
            GraphDefinition<Desk, DeskState, DeskId> desks) {
        return StateGraph.define(OFFICES)
                .initial(OfficeState.IDLE).terminal(OfficeState.CLOSED)
                .statePolicy(OfficeState.IDLE, "open up")
                .statePolicy(OfficeState.OPEN, "wait for the desk")
                .transition(OfficeId.OPEN_UP, OfficeState.IDLE, OfficeState.OPEN).endTransition()
                .transition(OfficeId.SHUT, OfficeState.OPEN, OfficeState.CLOSED).endTransition()
                .submachine(OfficeState.OPEN, desks, (Office value) -> Optional.of(value.desk),
                        (Desk value) -> Optional.empty())
                    .exit(DeskState.DONE, OfficeId.SHUT).endSubmachine()
                .build();
    }

    /** A relay whose stamping renews {@code desk}, one level deeper than a direct cascade would. */
    private static GraphDefinition<Relay, StampState, StampId> relayGraph(Desk desk) {
        return StateGraph.define(RELAYS)
                .initial(StampState.BLANK).terminal(StampState.STAMPED)
                .statePolicy(StampState.BLANK, "relay the renewal")
                .transition(StampId.STAMP, StampState.BLANK, StampState.STAMPED)
                    .action("Relay", (TransitionContext<Relay, StampState, StampId> ctx) -> {
                        ctx.cascade(DESKS, desk, DeskId.RENEW);
                        return ActionOutcome.ok();
                    }).endTransition()
                .build();
    }

    /**
     * Entering BOUND starts the first member, whose leaf is already done, so the holding's step defers the member's
     * exit SETTLE to its end. That exit's action makes the second member the holding's and renews BOUND, which starts
     * it; the second member's exit fails into PARKED, so the holding stays in BOUND. The first member then ends
     * SETTLED, but since the holding's deferred exit selected the first member's start, BOUND was entered again with
     * another member: no CLOSE runs.
     */
    @Test
    void aDescendantExitThatReentersTheNodeWithAnotherChildCancelsTheDeferredExit() {
        Member first = new Member("m1");
        Member second = new Member("m2");
        Holding holding = new Holding(first);
        second.holding = Optional.of(holding);
        GraphDefinition<Member, MemberLeafState, MemberLeafId> leaves = StateGraph.define(MEMBER_LEAVES)
                .initial(MemberLeafState.DONE).terminal(MemberLeafState.DONE).build();
        GraphDefinition<Member, MemberState, MemberId> members = memberGraph(leaves, first, second, holding);
        GraphDefinition<Holding, HoldingState, HoldingId> holdings = holdingGraph(members);
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(holdings).state((Holding value) -> value.state,
                        (Holding value, HoldingState state) -> value.state = state).identity((Holding value) -> "h"))
                .add(StateMachines.from(members).state((Member value) -> value.state,
                        (Member value, MemberState state) -> value.state = state)
                        .identity((Member value) -> value.id))
                .add(StateMachines.from(leaves).state((Member value) -> value.leaf,
                        (Member value, MemberLeafState state) -> value.leaf = state)
                        .identity((Member value) -> value.id))
                .build()) {
            Outcome<Holding, HoldingState, HoldingId> outcome = registry.of(HOLDINGS).execute(holding, HoldingId.ENTER);
            assertEquals(OutcomeCode.APPLIED, outcome.code());
            assertEquals(List.of("Holding:OPEN-ENTER>BOUND@0", "Member#m1:READY-SETTLE>SETTLED@1",
                    "Holding:BOUND-RENEW>BOUND@2", "Member#m2:READY-PARK>PARKED@3"), described(outcome.records()));
            assertEquals(HoldingState.BOUND, holding.state);
            assertEquals(MemberState.SETTLED, first.state);
            assertEquals(MemberState.PARKED, second.state);
        }
    }

    /**
     * A member whose READY state holds its {@code leaves}. Its exit SETTLE makes {@code second} the member of
     * {@code holding} and renews it when {@code first} settles, and fails into PARK for any other member.
     */
    private static GraphDefinition<Member, MemberState, MemberId> memberGraph(
            GraphDefinition<Member, MemberLeafState, MemberLeafId> leaves, Member first, Member second,
            Holding holding) {
        return StateGraph.define(MEMBERS)
                .initial(MemberState.READY).terminal(MemberState.SETTLED)
                .statePolicy(MemberState.READY, "wait for the leaf")
                .statePolicy(MemberState.PARKED, "settle later")
                .transition(MemberId.SETTLE, MemberState.READY, MemberState.SETTLED)
                    .action("Settle", (TransitionContext<Member, MemberState, MemberId> ctx) -> {
                        if (ctx.entity() != first) {
                            return ActionOutcome.failed("the second settlement waits");
                        }
                        holding.member = second;
                        ctx.cascade(HOLDINGS, holding, HoldingId.RENEW);
                        return ActionOutcome.ok();
                    }).onFailure(MemberId.PARK).endTransition()
                .transition(MemberId.PARK, MemberState.READY, MemberState.PARKED).endTransition()
                .transition(MemberId.LATER, MemberState.PARKED, MemberState.SETTLED).endTransition()
                .submachine(MemberState.READY, leaves).exit(MemberLeafState.DONE, MemberId.SETTLE).endSubmachine()
                .build();
    }

    /** A holding whose BOUND state holds its current member, and whose exit CLOSE a settled member selects. */
    private static GraphDefinition<Holding, HoldingState, HoldingId> holdingGraph(
            GraphDefinition<Member, MemberState, MemberId> members) {
        return StateGraph.define(HOLDINGS)
                .initial(HoldingState.OPEN).terminal(HoldingState.DONE).allowsCycle(HoldingState.BOUND)
                .statePolicy(HoldingState.OPEN, "enter the member")
                .statePolicy(HoldingState.BOUND, "renew or wait for the member")
                .transition(HoldingId.ENTER, HoldingState.OPEN, HoldingState.BOUND).endTransition()
                .transition(HoldingId.RENEW, HoldingState.BOUND, HoldingState.BOUND).endTransition()
                .transition(HoldingId.CLOSE, HoldingState.BOUND, HoldingState.DONE).endTransition()
                .submachine(HoldingState.BOUND, members, (Holding value) -> Optional.of(value.member),
                        (Member value) -> value.holding)
                    .exit(MemberState.SETTLED, HoldingId.CLOSE).endSubmachine()
                .build();
    }

    /**
     * BOOK starts the deposit and its action captures it; the capture's own action makes another payment the deposit.
     * The booking is in flight, so the exit runs at the end of BOOK's step, for the payment that BOOK started: the
     * engine takes it from its own log, not from the booking's childOf.
     */
    @Test
    void aChildReplacedDuringTheEnteringStepStillExitsItsParent() {
        Payment deposit = new Payment("d1", PaymentStatus.AUTHORIZING);
        Payment replacement = new Payment("d2", PaymentStatus.AUTHORIZING);
        Booking booking = new Booking("b1", BookingStatus.REQUESTED).attachDeposit(deposit)
                .attachBalance(new Payment("d3", PaymentStatus.AUTHORIZING));
        billing.bookingActions.behaviour = (TransitionContext<Booking, BookingStatus, BookingTransition> ctx) -> {
            if (ctx.transition() == BookingTransition.BOOK) {
                ctx.cascade(PAYMENTS, deposit, PaymentTransition.CAPTURE);
            }
            return ActionOutcome.ok();
        };
        billing.paymentActions.behaviour = (TransitionContext<Payment, PaymentStatus, PaymentTransition> ctx) -> {
            booking.attachDeposit(replacement);
            return ActionOutcome.ok();
        };
        try (MachineRegistry registry = billing.registry()) {
            Outcome<Booking, BookingStatus, BookingTransition> outcome =
                    registry.of(BOOKINGS).execute(booking, BookingTransition.BOOK);
            assertEquals(List.of("Booking#b1:REQUESTED-BOOK>DEPOSIT@0", "Payment#d1:AUTHORIZING-CAPTURE>CAPTURED@1",
                    "Booking#b1:DEPOSIT-DEPOSIT_PAID>BALANCE@1"), described(outcome.records()));
            assertEquals(BookingStatus.BALANCE, booking.status());
            assertEquals(PaymentStatus.CAPTURED, deposit.status());
            assertEquals(PaymentStatus.AUTHORIZING, replacement.status());
        }
    }

    /**
     * A payment left in a terminal state by an earlier run: entering the node again restarts it at its initial state,
     * which is not terminal, so the entry runs no exit.
     */
    @Test
    void reenteringTheNodeRestartsTheChildWithoutAnExit() {
        Payment deposit = new Payment("d1", PaymentStatus.CAPTURED);
        Booking booking = new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(deposit);
        try (MachineRegistry registry = billing.registry()) {
            Outcome<Booking, BookingStatus, BookingTransition> outcome =
                    registry.of(BOOKINGS).execute(booking, BookingTransition.RESTART);
            assertEquals(List.of("Booking#b1:DEPOSIT-RESTART>DEPOSIT@0"), described(outcome.records()));
            assertEquals(BookingStatus.DEPOSIT, booking.status());
            assertEquals(PaymentStatus.AUTHORIZING, deposit.status());
        }
    }

    /** Reentry during capture restarts the deposit without completing its replacement activation. */
    @Test
    void aChildRestartedByItsParentDuringItsActionDoesNotExitTheParent() {
        Payment deposit = new Payment("d1", PaymentStatus.AUTHORIZING);
        Booking booking = new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(deposit)
                .attachBalance(new Payment("d2", PaymentStatus.AUTHORIZING));
        billing.paymentActions.behaviour = (TransitionContext<Payment, PaymentStatus, PaymentTransition> ctx) -> {
            ctx.cascade(BOOKINGS, booking, BookingTransition.RESTART);
            return ActionOutcome.ok();
        };
        try (MachineRegistry registry = billing.registry()) {
            Outcome<Payment, PaymentStatus, PaymentTransition> outcome =
                    registry.of(PAYMENTS).execute(deposit, PaymentTransition.CAPTURE);
            assertEquals(BookingStatus.DEPOSIT, booking.status());
            assertEquals(PaymentStatus.AUTHORIZING, deposit.status());
            assertEquals(PaymentStatus.CAPTURED, outcome.recordedState());
            assertEquals(List.of("Payment#d1:AUTHORIZING-CAPTURE>CAPTURED@0",
                    "Booking#b1:DEPOSIT-RESTART>DEPOSIT@1"), described(outcome.records()));
            assertEquals(List.of(new SubmachineStart<>(PAYMENTS, deposit, PaymentStatus.CAPTURED,
                    PaymentStatus.AUTHORIZING, BOOKINGS, booking, BookingStatus.DEPOSIT, 1)), outcome.starts());
        }
    }

    /** The states of an office, whose OPEN state holds its desk. */
    enum OfficeState { IDLE, OPEN, CLOSED }

    /** Identifiers of the office machine: SHUT is the exit a done desk selects. */
    enum OfficeId { OPEN_UP, SHUT }

    /** The states of a desk, whose SERVING state, its initial one, holds its token. */
    enum DeskState { NEW, WAITING, SERVING, DONE }

    /** Identifiers of the desk machine: FINISH is the exit a served token selects. */
    enum DeskId { START, RESUME, BACK, RENEW, FINISH }

    /** The states of a token, whose QUEUED state, its initial one, holds its stamp. */
    enum TokenState { QUEUED, CALLED, SERVED }

    /** Identifiers of the token machine: SERVE is the exit a stamped stamp selects. */
    enum TokenId { CALL, MOVE, SERVE }

    /** The states of a stamp. */
    enum StampState { BLANK, STAMPED }

    /** The only identifier of the stamp machine. */
    enum StampId { STAMP }

    static final MachineKey<Office, OfficeState, OfficeId> OFFICES =
            MachineKey.of(Office.class, OfficeState.class, OfficeId.class);
    static final MachineKey<Desk, DeskState, DeskId> DESKS = MachineKey.of(Desk.class, DeskState.class, DeskId.class);
    static final MachineKey<Token, TokenState, TokenId> TOKENS =
            MachineKey.of(Token.class, TokenState.class, TokenId.class);
    static final MachineKey<Relay, StampState, StampId> RELAYS =
            MachineKey.of(Relay.class, StampState.class, StampId.class);
    static final MachineKey<Stamp, StampState, StampId> STAMPS =
            MachineKey.of(Stamp.class, StampState.class, StampId.class);

    /** An office and its desk. */
    static final class Office {
        private final Desk desk;
        private OfficeState state = OfficeState.IDLE;

        Office(Desk desk) {
            this.desk = desk;
        }

        @Override
        public String toString() { return "Office"; }
    }

    /** A desk and the token it currently serves. */
    static final class Desk {
        private Token token;
        private DeskState state = DeskState.NEW;

        Desk(Token token) {
            this.token = token;
        }

        @Override
        public String toString() { return "Desk"; }
    }

    /** A token, its stamp, and how many times its exit SERVE has run. */
    static final class Token {
        private final String name;
        private final Stamp stamp;
        private TokenState state = TokenState.CALLED;
        private int serves;

        Token(String name, Stamp stamp) {
            this.name = name;
            this.stamp = stamp;
        }

        @Override
        public String toString() { return "Token"; }
    }

    /** A relay whose STAMP action renews the desk one level deeper than a direct cascade would. */
    static final class Relay {
        private StampState state = StampState.BLANK;

        @Override
        public String toString() { return "Relay"; }
    }

    /** A stamp. */
    static final class Stamp {
        private final String name;
        private StampState state = StampState.STAMPED;

        Stamp(String name) {
            this.name = name;
        }

        @Override
        public String toString() { return "Stamp"; }
    }

    /** The states of a holding whose bound state holds its current member. */
    enum HoldingState { OPEN, BOUND, DONE }

    /** Identifiers of the holding machine: CLOSE is the exit a settled member selects. */
    enum HoldingId { ENTER, RENEW, CLOSE }

    /** The states of a member: READY holds its leaf, PARKED is where a failed settlement goes. */
    enum MemberState { READY, SETTLED, PARKED }

    /** Identifiers of the member machine: SETTLE is the exit its completed leaf selects. */
    enum MemberId { SETTLE, PARK, LATER }

    /** The leaf of a member starts in its only state, which is terminal. */
    enum MemberLeafState { DONE }

    /** The leaf declares no outgoing edge. */
    enum MemberLeafId { UNUSED }

    static final MachineKey<Holding, HoldingState, HoldingId> HOLDINGS =
            MachineKey.of(Holding.class, HoldingState.class, HoldingId.class);
    static final MachineKey<Member, MemberState, MemberId> MEMBERS =
            MachineKey.of(Member.class, MemberState.class, MemberId.class);
    static final MachineKey<Member, MemberLeafState, MemberLeafId> MEMBER_LEAVES =
            MachineKey.of(Member.class, MemberLeafState.class, MemberLeafId.class);

    /** A holding and the member it currently holds. */
    static final class Holding {
        private HoldingState state = HoldingState.OPEN;
        private Member member;

        Holding(Member member) {
            this.member = member;
            member.holding = Optional.of(this);
        }

        @Override
        public String toString() { return "Holding"; }
    }

    /** A member with its own state and its leaf's, and the holding it belongs to. */
    static final class Member {
        private final String id;
        private MemberState state = MemberState.READY;
        private MemberLeafState leaf = MemberLeafState.DONE;
        private Optional<Holding> holding = Optional.empty();

        Member(String id) {
            this.id = id;
        }

        @Override
        public String toString() { return "Member#" + id; }
    }

    /** Parent nodes that bind the same child and reserve the same exit identifier. */
    enum RacingParentState { BOUND, NEXT, DONE }

    /** A valid interruption and the reserved exit of each bound node. */
    enum RacingParentId { INTERRUPT, FINISH }

    /** The child restarts at PENDING and completes at END. */
    enum RacingChildState { PENDING, END }

    /** The asynchronous child operation used to park before the parent exit is selected. */
    enum RacingChildId { COMPLETE }

    /** Two states on the same entity, visible to both concurrent root calls. */
    static final class Racing {
        private volatile RacingParentState parent = RacingParentState.BOUND;
        private volatile RacingChildState child = RacingChildState.PENDING;
    }

    /** What the racing parent and child ran, counted to show that the stale completion ran none of it. */
    private static final class RacingRuns {
        private final AtomicInteger oldExitActions = new AtomicInteger();
        private final AtomicInteger newExitActions = new AtomicInteger();
        private final AtomicInteger parentWrites = new AtomicInteger();
        private final AtomicInteger childWrites = new AtomicInteger();
        private final AtomicInteger exitHooks = new AtomicInteger();
        private final AtomicInteger childHooks = new AtomicInteger();
        private final AtomicInteger interruptionHooks = new AtomicInteger();
        private final AtomicInteger watchLookups = new AtomicInteger();
    }

    /** The child restarts at PENDING and completes at END once {@code release} completes its action's stage. */
    private static GraphDefinition<Racing, RacingChildState, RacingChildId> racingChild(
            MachineKey<Racing, RacingChildState, RacingChildId> children, CompletableFuture<Boolean> invoked,
            CompletableFuture<ActionOutcome> release, RacingRuns runs) {
        return StateGraph.define(children)
                .initial(RacingChildState.PENDING).terminal(RacingChildState.END)
                .statePolicy(RacingChildState.PENDING, "wait for completion")
                .transition(RacingChildId.COMPLETE, RacingChildState.PENDING, RacingChildState.END)
                    .asyncAction("Park", (TransitionContext<Racing, RacingChildState, RacingChildId> ctx) -> {
                        invoked.complete(true);
                        return release;
                    }).afterCommitWith("ChildAudit",
                            (Racing value, CommitContext<RacingChildState, RacingChildId> hop) ->
                            runs.childHooks.incrementAndGet()).endTransition().build();
    }

    /**
     * The parent whose two bound nodes, BOUND and NEXT, bind {@code childGraph} and reserve the exit FINISH for a
     * completed child; a watch on BOUND executes INTERRUPT, which moves it to NEXT, once a child has ended.
     */
    private static GraphDefinition<Racing, RacingParentState, RacingParentId> racingParent(
            MachineKey<Racing, RacingParentState, RacingParentId> parents,
            MachineKey<Racing, RacingChildState, RacingChildId> children,
            GraphDefinition<Racing, RacingChildState, RacingChildId> childGraph, RacingRuns runs) {
        return StateGraph.define(parents)
                .initial(RacingParentState.BOUND).terminal(RacingParentState.DONE)
                .statePolicy(RacingParentState.BOUND, "wait for the child")
                .statePolicy(RacingParentState.NEXT, "wait for the restarted child")
                .transition(RacingParentId.INTERRUPT, RacingParentState.BOUND, RacingParentState.NEXT)
                    .afterCommitWith("InterruptionAudit",
                            (Racing value, CommitContext<RacingParentState, RacingParentId> hop) ->
                                    runs.interruptionHooks.incrementAndGet()).endTransition()
                .transition(RacingParentId.FINISH, RacingParentState.BOUND, RacingParentState.DONE)
                    .action("OldExit", (TransitionContext<Racing, RacingParentState, RacingParentId> ctx) -> {
                        runs.oldExitActions.incrementAndGet();
                        return ActionOutcome.ok();
                    }).afterCommitWith("ExitAudit",
                            (Racing value, CommitContext<RacingParentState, RacingParentId> hop) ->
                            runs.exitHooks.incrementAndGet()).endTransition()
                .transition(RacingParentId.FINISH, RacingParentState.NEXT, RacingParentState.DONE)
                    .action("NewExit", (TransitionContext<Racing, RacingParentState, RacingParentId> ctx) -> {
                        runs.newExitActions.incrementAndGet();
                        return ActionOutcome.ok();
                    }).afterCommitWith("ExitAudit",
                            (Racing value, CommitContext<RacingParentState, RacingParentId> hop) ->
                            runs.exitHooks.incrementAndGet()).endTransition()
                .watch(RacingParentState.BOUND, children, (Racing entity) -> List.of(entity), (Racing entity) -> {
                    runs.watchLookups.incrementAndGet();
                    return Optional.of(entity);
                }).when(WatchCondition.anyIn(RacingChildState.END))
                    .execute(RacingParentId.INTERRUPT).endWatch()
                .submachine(RacingParentState.BOUND, childGraph)
                    .exit(RacingChildState.END, RacingParentId.FINISH).endSubmachine()
                .submachine(RacingParentState.NEXT, childGraph)
                    .exit(RacingChildState.END, RacingParentId.FINISH).endSubmachine().build();
    }

    /** The parent and the child over {@link Racing}, whose setters count their writes in {@code runs}. */
    private static MachineRegistry racingRegistry(
            GraphDefinition<Racing, RacingParentState, RacingParentId> parentGraph,
            GraphDefinition<Racing, RacingChildState, RacingChildId> childGraph, RacingRuns runs) {
        return MachineRegistry.builder()
                .add(StateMachines.from(parentGraph).state((Racing value) -> value.parent,
                        (Racing value, RacingParentState state) -> {
                            runs.parentWrites.incrementAndGet();
                            value.parent = state;
                        }).identity((Racing value) -> "one"))
                .add(StateMachines.from(childGraph).state((Racing value) -> value.child,
                        (Racing value, RacingChildState state) -> {
                            runs.childWrites.incrementAndGet();
                            value.child = state;
                        }).identity((Racing value) -> "one").asyncTimeout(Duration.ofSeconds(10))).build();
    }

    /** A delayed child completion retains its original binding's source and conflicts before any stale writes. */
    @Test
    void aDelayedChildCompletionCannotExitTheParentsNewBoundNode() throws Exception {
        MachineKey<Racing, RacingParentState, RacingParentId> parents =
                MachineKey.of(Racing.class, RacingParentState.class, RacingParentId.class);
        MachineKey<Racing, RacingChildState, RacingChildId> children =
                MachineKey.of(Racing.class, RacingChildState.class, RacingChildId.class);
        CompletableFuture<Boolean> invoked = new CompletableFuture<>();
        CompletableFuture<ActionOutcome> release = new CompletableFuture<>();
        RacingRuns runs = new RacingRuns();
        GraphDefinition<Racing, RacingChildState, RacingChildId> childGraph =
                racingChild(children, invoked, release, runs);
        GraphDefinition<Racing, RacingParentState, RacingParentId> parentGraph =
                racingParent(parents, children, childGraph, runs);
        Racing entity = new Racing();
        try (MachineRegistry registry = racingRegistry(parentGraph, childGraph, runs)) {
            CompletableFuture<Outcome<Racing, RacingChildState, RacingChildId>> stale =
                    registry.of(children).async().execute(entity, RacingChildId.COMPLETE).toCompletableFuture();
            try {
                assertEquals(true, invoked.get(5, TimeUnit.SECONDS));
                Outcome<Racing, RacingParentState, RacingParentId> interruption =
                        registry.of(parents).execute(entity, RacingParentId.INTERRUPT);
                assertEquals(OutcomeCode.APPLIED, interruption.code());
                assertEquals(RacingParentState.NEXT, entity.parent);
                assertEquals(RacingChildState.PENDING, entity.child);
                assertEquals(List.of(new SubmachineStart<>(children, entity, RacingChildState.PENDING,
                        RacingChildState.PENDING, parents, entity, RacingParentState.NEXT, 0)), interruption.starts());
                assertEquals(1, interruption.records().size());
                assertEquals(OutcomeCode.RESERVED_EXIT,
                        registry.of(parents).execute(entity, RacingParentId.FINISH).code());
                release.complete(ActionOutcome.ok());
                ExecutionException failed = assertThrows(ExecutionException.class,
                        () -> stale.get(5, TimeUnit.SECONDS));
                assertTheStaleCompletionFailedTheCommitCheckAndWroteNothing(failed, entity, runs);
            } finally {
                release.complete(ActionOutcome.ok());
            }
        }
    }

    private static void assertTheStaleCompletionFailedTheCommitCheckAndWroteNothing(ExecutionException failed,
                                                                                    Racing entity, RacingRuns runs) {
        CommitFailedException conflict = (CommitFailedException) failed.getCause();
        assertEquals(CallPhase.COMMIT, conflict.phase());
        assertEquals(StatesWritten.NONE, conflict.statesWritten());
        assertEquals(List.of(), conflict.applied());
        assertEquals(Optional.empty(), conflict.failedEntity());
        assertEquals(List.of(entity, entity), conflict.pending());
        assertEquals(RacingParentState.NEXT, entity.parent);
        assertEquals(RacingChildState.PENDING, entity.child);
        assertEquals(1, runs.oldExitActions.get());
        assertEquals(0, runs.newExitActions.get());
        assertEquals(1, runs.parentWrites.get());
        assertEquals(1, runs.childWrites.get());
        assertEquals(1, runs.interruptionHooks.get());
        assertEquals(0, runs.exitHooks.get());
        assertEquals(0, runs.childHooks.get());
        assertEquals(0, runs.watchLookups.get());
    }
}
