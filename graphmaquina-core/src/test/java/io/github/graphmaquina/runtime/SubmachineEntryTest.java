package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.Billing.BOOKINGS;
import static io.github.graphmaquina.fixtures.Billing.PAYMENTS;
import static io.github.graphmaquina.fixtures.Billing.RISKS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.OutcomeCode;
import io.github.graphmaquina.api.Reaction;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.StatesWritten;
import io.github.graphmaquina.api.SubmachineStart;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.TransitionKind;
import io.github.graphmaquina.api.TransitionRecord;
import io.github.graphmaquina.error.AfterCommitException;
import io.github.graphmaquina.error.AmbiguousIdentityException;
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
import io.github.graphmaquina.fixtures.Billing.RiskStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

/**
 * Entering a bound node starts the child at its initial state, in the same unit of work as the hop that entered:
 * through an edge, a self-loop or a failure route, at the root or inside a cascade, and recursively when the child's
 * initial state is bound too. A start is logged right after that hop, before its action, so a rollback drops it and a
 * cascade from the entering action finds the child at its initial state. It is no hop: it runs no hook and is not in
 * {@code records()}, and {@code starts()} reports it. A start that cannot find or read its child aborts the call.
 */
class SubmachineEntryTest {

    private final Billing billing = new Billing();

    /** The start the call reports for {@code child} of the payment machine, entered from {@code previous}. */
    private static SubmachineStart<Payment, PaymentStatus, PaymentTransition> start(
            Payment child, PaymentStatus previous, Booking parent, BookingStatus node, int depth) {
        return new SubmachineStart<>(PAYMENTS, child, previous, PaymentStatus.AUTHORIZING, BOOKINGS, parent, node,
                depth);
    }

    private static <T> T single(List<T> values) {
        assertEquals(1, values.size(), values.toString());
        return values.get(0);
    }

    private static String describe(TransitionRecord<?, ?, ?> record) {
        return record.entity() + ":" + record.from() + "-" + record.transition() + ">" + record.to() + "@"
                + record.depth();
    }

    private static List<String> described(List<TransitionRecord<?, ?, ?>> records) {
        return records.stream().map(SubmachineEntryTest::describe).toList();
    }

    @Test
    void enteringABoundNodeStartsTheChildFromWhereItWas() {
        Payment deposit = new Payment("d1", PaymentStatus.CAPTURED);
        Booking booking = new Booking("b1", BookingStatus.REQUESTED).attachDeposit(deposit);
        try (MachineRegistry registry = billing.registry()) {
            Outcome<Booking, BookingStatus, BookingTransition> outcome =
                    registry.of(BOOKINGS).execute(booking, BookingTransition.BOOK);
            assertEquals(OutcomeCode.APPLIED, outcome.code());
            assertEquals(List.of(start(deposit, PaymentStatus.CAPTURED, booking, BookingStatus.DEPOSIT, 0)),
                    outcome.starts());
            SubmachineStart<?, ?, ?> start = single(outcome.starts());
            assertSame(deposit, start.child());
            assertSame(booking, start.parent());
            assertEquals(BookingStatus.DEPOSIT, booking.status());
            assertEquals(PaymentStatus.AUTHORIZING, deposit.status());
            TransitionRecord<?, ?, ?> hop = single(outcome.records());
            assertEquals("Booking#b1:REQUESTED-BOOK>DEPOSIT@0", describe(hop));
            assertEquals(TransitionKind.ACTION, hop.kind());
            assertEquals(List.of("Booking#b1:REQUESTED-BOOK>DEPOSIT"), billing.hooks);
            assertEquals(List.of("Booking#b1:REQUESTED-BOOK>DEPOSIT@0"), billing.bookingActions.calls);
        }
    }

    /** The payment the start moved is watched: the booking that awaits it is re-evaluated after the commit. */
    @Test
    void aStartFromAnotherStateMovesTheChildAndDispatchesItsWatches() {
        Payment deposit = new Payment("d1", PaymentStatus.CAPTURED);
        Booking booking = new Booking("b1", BookingStatus.REQUESTED).attachDeposit(deposit);
        Booking follower = new Booking("b2", BookingStatus.REQUESTED)
                .attachDeposit(new Payment("d2", PaymentStatus.VOIDED)).awaits(deposit);
        try (MachineRegistry registry = billing.registry()) {
            Outcome<Booking, BookingStatus, BookingTransition> outcome =
                    registry.of(BOOKINGS).execute(booking, BookingTransition.BOOK);
            Reaction.Done reaction = assertInstanceOf(Reaction.Done.class, single(outcome.reactions()));
            assertSame(follower, reaction.entity());
            assertEquals(OutcomeCode.APPLIED, reaction.outcome().code());
            assertEquals(BookingStatus.DEPOSIT, follower.status());
        }
    }

    /** A child already at its initial state does not move, so nothing watching it is dispatched. */
    @Test
    void aStartFromTheChildsOwnInitialStateMovesNothing() {
        Payment deposit = new Payment("d1", PaymentStatus.AUTHORIZING);
        Booking booking = new Booking("b1", BookingStatus.REQUESTED).attachDeposit(deposit);
        Booking follower = new Booking("b2", BookingStatus.REQUESTED)
                .attachDeposit(new Payment("d2", PaymentStatus.VOIDED)).awaits(deposit);
        try (MachineRegistry registry = billing.registry()) {
            Outcome<Booking, BookingStatus, BookingTransition> outcome =
                    registry.of(BOOKINGS).execute(booking, BookingTransition.BOOK);
            assertEquals(List.of(start(deposit, PaymentStatus.AUTHORIZING, booking, BookingStatus.DEPOSIT, 0)),
                    outcome.starts());
            assertEquals(List.of(), outcome.reactions());
            assertEquals(PaymentStatus.AUTHORIZING, deposit.status());
            assertEquals(BookingStatus.REQUESTED, follower.status());
        }
    }

    /** Review Focus #2: a watch reaction that enters a bound node reports the start in its own outcome. */
    @Test
    void aWatchReactionThatEntersABoundNodeCarriesItsStartInItsOwnOutcome() {
        Payment deposit = new Payment("d1", PaymentStatus.CAPTURED);
        Booking booking = new Booking("b1", BookingStatus.REQUESTED).attachDeposit(deposit);
        Payment followerDeposit = new Payment("d2", PaymentStatus.VOIDED);
        Booking follower = new Booking("b2", BookingStatus.REQUESTED).attachDeposit(followerDeposit).awaits(deposit);
        try (MachineRegistry registry = billing.registry()) {
            Outcome<Booking, BookingStatus, BookingTransition> outcome =
                    registry.of(BOOKINGS).execute(booking, BookingTransition.BOOK);
            assertEquals(List.of(start(deposit, PaymentStatus.CAPTURED, booking, BookingStatus.DEPOSIT, 0)),
                    outcome.starts());
            Reaction.Done reaction = assertInstanceOf(Reaction.Done.class, single(outcome.reactions()));
            assertEquals(Optional.of(BookingTransition.BOOK), reaction.outcome().requestedTransition());
            assertEquals(List.of(start(followerDeposit, PaymentStatus.VOIDED, follower, BookingStatus.DEPOSIT, 0)),
                    reaction.outcome().starts());
            assertEquals(List.of("Booking#b2:REQUESTED-BOOK>DEPOSIT@0"), described(reaction.outcome().records()));
            assertEquals(PaymentStatus.AUTHORIZING, followerDeposit.status());
        }
    }

    /** A self-loop on the bound node enters it again: the child restarts from wherever it got to. */
    @Test
    void aSelfLoopOnTheBoundNodeRestartsTheChild() {
        Payment deposit = new Payment("d1", PaymentStatus.HELD);
        Booking booking = new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(deposit);
        try (MachineRegistry registry = billing.registry()) {
            Outcome<Booking, BookingStatus, BookingTransition> outcome =
                    registry.of(BOOKINGS).execute(booking, BookingTransition.RESTART);
            assertEquals(List.of(start(deposit, PaymentStatus.HELD, booking, BookingStatus.DEPOSIT, 0)),
                    outcome.starts());
            assertEquals(PaymentStatus.AUTHORIZING, deposit.status());
            assertEquals(List.of("Booking#b1:DEPOSIT-RESTART>DEPOSIT"), billing.hooks);
        }
    }

    /**
     * PAY_IN_FULL enters BALANCE, which starts the balance payment while its action runs. The action fails, the
     * rollback drops that start with the hop, and the failure route BOOK enters DEPOSIT, which starts the deposit.
     */
    @Test
    void aFailureRouteDropsTheFailingEdgesStartAndStartsTheChildOfTheNodeItEnters() {
        Payment deposit = new Payment("d1", PaymentStatus.CAPTURED);
        Payment balance = new Payment("d2", PaymentStatus.CAPTURED);
        Booking booking = new Booking("b1", BookingStatus.REQUESTED).attachDeposit(deposit).attachBalance(balance);
        List<PaymentStatus> seen = new ArrayList<>();
        billing.bookingActions.behaviour = (TransitionContext<Booking, BookingStatus, BookingTransition> ctx) -> {
            if (ctx.transition() == BookingTransition.PAY_IN_FULL) {
                seen.add(ctx.peer(PAYMENTS).stateOf(balance));
                return ActionOutcome.failed("the card was declined");
            }
            return ActionOutcome.ok();
        };
        try (MachineRegistry registry = billing.registry()) {
            Outcome<Booking, BookingStatus, BookingTransition> outcome =
                    registry.of(BOOKINGS).execute(booking, BookingTransition.PAY_IN_FULL);
            Outcome.Failed<Booking, BookingStatus, BookingTransition> failed = outcome.asFailed().orElseThrow();
            assertEquals(BookingTransition.BOOK, failed.failureTransition());
            assertEquals(List.of(PaymentStatus.AUTHORIZING), seen);
            assertEquals(List.of(start(deposit, PaymentStatus.CAPTURED, booking, BookingStatus.DEPOSIT, 0)),
                    outcome.starts());
            assertEquals(List.of("Booking#b1:REQUESTED-BOOK>DEPOSIT@0"), described(outcome.records()));
            assertEquals(BookingStatus.DEPOSIT, booking.status());
            assertEquals(PaymentStatus.AUTHORIZING, deposit.status());
            assertEquals(PaymentStatus.CAPTURED, balance.status());
        }
    }

    /**
     * The start is logged before the entering action runs: a cascade from that action finds the child at its initial
     * state. The cascade's own outcome carries no start and no record; the root's carries both, and the hooks run for
     * the two hops only.
     */
    @Test
    void aCascadeFromTheEnteringActionFindsTheChildAtItsInitialState() {
        Payment deposit = new Payment("d1", PaymentStatus.CAPTURED);
        Booking booking = new Booking("b1", BookingStatus.REQUESTED).attachDeposit(deposit);
        List<Outcome<Payment, PaymentStatus, PaymentTransition>> cascaded = new ArrayList<>();
        billing.bookingActions.behaviour = (TransitionContext<Booking, BookingStatus, BookingTransition> ctx) -> {
            cascaded.add(ctx.cascade(PAYMENTS, deposit, PaymentTransition.HOLD));
            return ActionOutcome.ok();
        };
        try (MachineRegistry registry = billing.registry()) {
            Outcome<Booking, BookingStatus, BookingTransition> outcome =
                    registry.of(BOOKINGS).execute(booking, BookingTransition.BOOK);
            Outcome<Payment, PaymentStatus, PaymentTransition> hold = single(cascaded);
            assertEquals(PaymentStatus.AUTHORIZING, hold.from());
            assertEquals(PaymentStatus.HELD, hold.recordedState());
            assertEquals(List.of(), hold.starts());
            assertEquals(List.of(), hold.records());
            assertEquals(List.of(start(deposit, PaymentStatus.CAPTURED, booking, BookingStatus.DEPOSIT, 0)),
                    outcome.starts());
            assertEquals(List.of("Booking#b1:REQUESTED-BOOK>DEPOSIT@0", "Payment#d1:AUTHORIZING-HOLD>HELD@1"),
                    described(outcome.records()));
            assertEquals(List.of("Booking#b1:REQUESTED-BOOK>DEPOSIT", "Payment#d1:AUTHORIZING-HOLD>HELD"),
                    billing.hooks);
            assertEquals(PaymentStatus.HELD, deposit.status());
        }
    }

    /** A cascade that enters the node starts the child at the cascade's depth; the root reports the start. */
    @Test
    void aCascadeThatEntersABoundNodeStartsTheChildAtItsDepth() {
        Payment deposit = new Payment("d1", PaymentStatus.DECLINED);
        Booking other = new Booking("b2", BookingStatus.REQUESTED).attachDeposit(deposit);
        Booking booking = new Booking("b1", BookingStatus.REQUESTED);
        List<Outcome<Booking, BookingStatus, BookingTransition>> cascaded = new ArrayList<>();
        billing.bookingActions.behaviour = (TransitionContext<Booking, BookingStatus, BookingTransition> ctx) -> {
            if (ctx.transition() == BookingTransition.CANCEL) {
                cascaded.add(ctx.cascade(BOOKINGS, other, BookingTransition.BOOK));
            }
            return ActionOutcome.ok();
        };
        try (MachineRegistry registry = billing.registry()) {
            Outcome<Booking, BookingStatus, BookingTransition> outcome =
                    registry.of(BOOKINGS).execute(booking, BookingTransition.CANCEL);
            assertEquals(List.of(start(deposit, PaymentStatus.DECLINED, other, BookingStatus.DEPOSIT, 1)),
                    outcome.starts());
            assertEquals(List.of(), single(cascaded).starts());
            assertEquals(List.of("Booking#b1:REQUESTED-CANCEL>CANCELLED@0", "Booking#b2:REQUESTED-BOOK>DEPOSIT@1"),
                    described(outcome.records()));
            assertEquals(PaymentStatus.AUTHORIZING, deposit.status());
        }
    }

    /** Two entries that share one child in a call: the second start reads the state the first one left pending. */
    @Test
    void aSecondStartOfTheSameChildReadsItsPendingState() {
        Payment deposit = new Payment("d1", PaymentStatus.VOIDED);
        Booking first = new Booking("b2", BookingStatus.REQUESTED).attachDeposit(deposit);
        Booking second = new Booking("b3", BookingStatus.REQUESTED).attachDeposit(deposit);
        Booking booking = new Booking("b1", BookingStatus.REQUESTED);
        billing.bookingActions.behaviour = (TransitionContext<Booking, BookingStatus, BookingTransition> ctx) -> {
            if (ctx.transition() == BookingTransition.CANCEL) {
                ctx.cascade(BOOKINGS, first, BookingTransition.BOOK);
                ctx.cascade(BOOKINGS, second, BookingTransition.BOOK);
            }
            return ActionOutcome.ok();
        };
        try (MachineRegistry registry = billing.registry()) {
            Outcome<Booking, BookingStatus, BookingTransition> outcome =
                    registry.of(BOOKINGS).execute(booking, BookingTransition.CANCEL);
            assertEquals(List.of(start(deposit, PaymentStatus.VOIDED, first, BookingStatus.DEPOSIT, 1),
                            start(deposit, PaymentStatus.AUTHORIZING, second, BookingStatus.DEPOSIT, 1)),
                    outcome.starts());
            assertEquals(PaymentStatus.AUTHORIZING, deposit.status());
        }
    }

    /** The risk check is bound to the payment's initial state, so starting the payment starts it too. */
    @Test
    void aChildWhoseInitialStateIsBoundStartsItsOwnChild() {
        Billing risky = Billing.withRiskCheck();
        Payment deposit = new Payment("d1", PaymentStatus.CAPTURED);
        deposit.setRisk(RiskStatus.FLAGGED);
        Booking booking = new Booking("b1", BookingStatus.REQUESTED).attachDeposit(deposit);
        try (MachineRegistry registry = risky.registry()) {
            Outcome<Booking, BookingStatus, BookingTransition> outcome =
                    registry.of(BOOKINGS).execute(booking, BookingTransition.BOOK);
            assertEquals(List.of(start(deposit, PaymentStatus.CAPTURED, booking, BookingStatus.DEPOSIT, 0),
                            new SubmachineStart<>(RISKS, deposit, RiskStatus.FLAGGED, RiskStatus.SCORING, PAYMENTS,
                                    deposit, PaymentStatus.AUTHORIZING, 0)),
                    outcome.starts());
            assertEquals(PaymentStatus.AUTHORIZING, deposit.status());
            assertEquals(RiskStatus.SCORING, deposit.risk());
            assertEquals(List.of("Booking#b1:REQUESTED-BOOK>DEPOSIT"), risky.hooks);
        }
    }

    /** A hook that throws after the commit: the exception carries the starts the call committed. */
    @Test
    void anAfterCommitFailureCarriesTheStartsOfTheCall() {
        Payment deposit = new Payment("d1", PaymentStatus.CAPTURED);
        Booking booking = new Booking("b1", BookingStatus.REQUESTED).attachDeposit(deposit);
        IllegalStateException down = new IllegalStateException("the audit is down");
        billing.afterHook = (String hop) -> {
            throw down;
        };
        try (MachineRegistry registry = billing.registry()) {
            StateMachine<Booking, BookingStatus, BookingTransition> bookings = registry.of(BOOKINGS);
            AfterCommitException error = assertThrows(AfterCommitException.class,
                    () -> bookings.execute(booking, BookingTransition.BOOK));
            assertSame(down, error.getCause());
            assertEquals("Audit", error.hook());
            assertEquals(List.of(start(deposit, PaymentStatus.CAPTURED, booking, BookingStatus.DEPOSIT, 0)),
                    error.starts());
            assertEquals(List.of("Booking#b1:REQUESTED-BOOK>DEPOSIT@0"), described(error.records()));
            assertEquals(BookingStatus.DEPOSIT, booking.status());
            assertEquals(PaymentStatus.AUTHORIZING, deposit.status());
        }
    }

    @Test
    void enteringWithNoChildAbortsBeforeTheAction() {
        Booking booking = new Booking("b1", BookingStatus.REQUESTED);
        try (MachineRegistry registry = billing.registry()) {
            StateMachine<Booking, BookingStatus, BookingTransition> bookings = registry.of(BOOKINGS);
            TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                    () -> bookings.execute(booking, BookingTransition.BOOK));
            assertEquals(CallPhase.ACTION, error.phase());
            assertEquals(StatesWritten.NONE, error.statesWritten());
            assertEquals("transition aborted in Booking/BookingStatus REQUESTED -BOOK-> DEPOSIT: Booking/BookingStatus"
                    + " entered DEPOSIT with no Payment/PaymentStatus [route Booking/BookingStatus#b1]",
                    error.getMessage());
            assertEquals(Optional.empty(), Optional.ofNullable(error.getCause()));
            assertEquals(BookingStatus.REQUESTED, booking.status());
            assertEquals(List.of(), billing.bookingActions.calls);
        }
    }

    /** childOf breaks its contract when it returns null instead of an Optional. */
    @Test
    void aChildOfThatReturnsNullAborts() {
        Booking booking = new Booking("b1", BookingStatus.REQUESTED)
                .attachDeposit(new Payment("d1", PaymentStatus.CAPTURED));
        billing.depositOf = (Booking entered) -> null;
        try (MachineRegistry registry = billing.registry()) {
            StateMachine<Booking, BookingStatus, BookingTransition> bookings = registry.of(BOOKINGS);
            TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                    () -> bookings.execute(booking, BookingTransition.BOOK));
            assertEquals(CallPhase.ACTION, error.phase());
            assertEquals("transition aborted in Booking/BookingStatus REQUESTED -BOOK-> DEPOSIT: the childOf of"
                    + " Booking/BookingStatus.DEPOSIT returned null [route Booking/BookingStatus#b1]",
                    error.getMessage());
            assertEquals(BookingStatus.REQUESTED, booking.status());
        }
    }

    /** What childOf throws is the cause, unwrapped from CompletionException. */
    @Test
    void aChildOfThatThrowsAbortsWithWhatItThrew() {
        Booking booking = new Booking("b1", BookingStatus.REQUESTED)
                .attachDeposit(new Payment("d1", PaymentStatus.CAPTURED));
        IllegalStateException failure = new IllegalStateException("the ledger is down");
        billing.depositOf = (Booking entered) -> {
            throw new CompletionException(failure);
        };
        try (MachineRegistry registry = billing.registry()) {
            StateMachine<Booking, BookingStatus, BookingTransition> bookings = registry.of(BOOKINGS);
            TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                    () -> bookings.execute(booking, BookingTransition.BOOK));
            assertEquals(CallPhase.ACTION, error.phase());
            assertSame(failure, error.getCause());
            assertEquals("transition aborted in Booking/BookingStatus REQUESTED -BOOK-> DEPOSIT: the childOf of"
                    + " Booking/BookingStatus.DEPOSIT threw java.lang.IllegalStateException: the ledger is down"
                    + " [route Booking/BookingStatus#b1]", error.getMessage());
        }
    }

    @Test
    void anErrorThatChildOfThrowsPropagates() {
        Booking booking = new Booking("b1", BookingStatus.REQUESTED)
                .attachDeposit(new Payment("d1", PaymentStatus.CAPTURED));
        AssertionError broken = new AssertionError("broken lookup");
        billing.depositOf = (Booking entered) -> {
            throw broken;
        };
        try (MachineRegistry registry = billing.registry()) {
            StateMachine<Booking, BookingStatus, BookingTransition> bookings = registry.of(BOOKINGS);
            assertSame(broken, assertThrows(AssertionError.class,
                    () -> bookings.execute(booking, BookingTransition.BOOK)));
            assertEquals(BookingStatus.REQUESTED, booking.status());
        }
    }

    /** childOf runs under the root's reentrancy marker: a transition it starts is refused, and the call aborts. */
    @Test
    void aChildOfThatStartsATransitionAbortsTheCall() {
        Payment deposit = new Payment("d1", PaymentStatus.CAPTURED);
        Booking booking = new Booking("b1", BookingStatus.REQUESTED).attachDeposit(deposit);
        try (MachineRegistry registry = billing.registry()) {
            StateMachine<Booking, BookingStatus, BookingTransition> bookings = registry.of(BOOKINGS);
            billing.depositOf = (Booking entered) -> {
                registry.of(PAYMENTS).execute(deposit, PaymentTransition.HOLD);
                return Optional.of(deposit);
            };
            TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                    () -> bookings.execute(booking, BookingTransition.BOOK));
            assertEquals(CallPhase.ACTION, error.phase());
            assertInstanceOf(ReentrantTransitionException.class, error.getCause());
            assertEquals(PaymentStatus.CAPTURED, deposit.status());
            assertEquals(BookingStatus.REQUESTED, booking.status());
        }
    }

    /** The child's state is an input: a null state is refused, as a root's or a cascaded peer's is. */
    @Test
    void aChildWithAnUninitializedStateAborts() {
        Payment deposit = new Payment("d1", null);
        Booking booking = new Booking("b1", BookingStatus.REQUESTED).attachDeposit(deposit);
        try (MachineRegistry registry = billing.registry()) {
            StateMachine<Booking, BookingStatus, BookingTransition> bookings = registry.of(BOOKINGS);
            TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                    () -> bookings.execute(booking, BookingTransition.BOOK));
            assertEquals(CallPhase.ACTION, error.phase());
            UninitializedStateException cause = assertInstanceOf(UninitializedStateException.class, error.getCause());
            assertEquals("transition aborted in Booking/BookingStatus REQUESTED -BOOK-> DEPOSIT: reading the"
                    + " sub-machine of Booking/BookingStatus.DEPOSIT threw " + cause
                    + " [route Booking/BookingStatus#b1]", error.getMessage());
            assertEquals(BookingStatus.REQUESTED, booking.status());
        }
    }

    /**
     * Inside a cascade the failed entry poisons the transaction: the action that cascaded catches the abort and
     * returns ok, and the root still throws that very abort.
     */
    @Test
    void aFailedEntryInsideACascadePoisonsTheTransaction() {
        Booking other = new Booking("b2", BookingStatus.REQUESTED);
        Booking booking = new Booking("b1", BookingStatus.REQUESTED);
        List<RuntimeException> caught = new ArrayList<>();
        billing.bookingActions.behaviour = (TransitionContext<Booking, BookingStatus, BookingTransition> ctx) -> {
            try {
                ctx.cascade(BOOKINGS, other, BookingTransition.BOOK);
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
            assertEquals("transition aborted in Booking/BookingStatus REQUESTED -BOOK-> DEPOSIT: Booking/BookingStatus"
                    + " entered DEPOSIT with no Payment/PaymentStatus [route Booking/BookingStatus#b1 →"
                    + " Booking/BookingStatus#b2]", error.getMessage());
            assertEquals(BookingStatus.REQUESTED, booking.status());
            assertEquals(BookingStatus.REQUESTED, other.status());
        }
    }

    /** The child joins the unit of work by its machine's identity: two instances with one identity are refused. */
    @Test
    void twoChildInstancesWithOneIdentityAreAmbiguous() {
        Payment deposit = new Payment("d1", PaymentStatus.CAPTURED);
        Payment copy = new Payment("d1", PaymentStatus.CAPTURED);
        Booking first = new Booking("b2", BookingStatus.REQUESTED).attachDeposit(deposit);
        Booking second = new Booking("b3", BookingStatus.REQUESTED).attachDeposit(copy);
        Booking booking = new Booking("b1", BookingStatus.REQUESTED);
        billing.bookingActions.behaviour = (TransitionContext<Booking, BookingStatus, BookingTransition> ctx) -> {
            if (ctx.transition() == BookingTransition.CANCEL) {
                ctx.cascade(BOOKINGS, first, BookingTransition.BOOK);
                ctx.cascade(BOOKINGS, second, BookingTransition.BOOK);
            }
            return ActionOutcome.ok();
        };
        try (MachineRegistry registry = billing.registry()) {
            StateMachine<Booking, BookingStatus, BookingTransition> bookings = registry.of(BOOKINGS);
            TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                    () -> bookings.execute(booking, BookingTransition.CANCEL));
            assertInstanceOf(AmbiguousIdentityException.class, error.getCause());
            assertEquals(PaymentStatus.CAPTURED, deposit.status());
            assertEquals(BookingStatus.REQUESTED, first.status());
        }
    }
}
