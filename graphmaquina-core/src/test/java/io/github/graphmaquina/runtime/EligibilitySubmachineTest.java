package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.api.RejectionKind.DETACHED;
import static io.github.graphmaquina.api.RejectionKind.NO_TRANSITION;
import static io.github.graphmaquina.api.RejectionKind.RESERVED_EXIT;
import static io.github.graphmaquina.fixtures.Billing.BOOKINGS;
import static io.github.graphmaquina.fixtures.Billing.BookingStatus.DEPOSIT;
import static io.github.graphmaquina.fixtures.Billing.BookingStatus.REQUESTED;
import static io.github.graphmaquina.fixtures.Billing.BookingTransition.BOOK;
import static io.github.graphmaquina.fixtures.Billing.BookingTransition.CANCEL;
import static io.github.graphmaquina.fixtures.Billing.BookingTransition.DEPOSIT_PAID;
import static io.github.graphmaquina.fixtures.Billing.PAYMENTS;
import static io.github.graphmaquina.fixtures.Billing.PaymentStatus.AUTHORIZING;
import static io.github.graphmaquina.fixtures.Billing.PaymentStatus.CAPTURED;
import static io.github.graphmaquina.fixtures.Billing.PaymentStatus.HELD;
import static io.github.graphmaquina.fixtures.Billing.PaymentTransition.CAPTURE;
import static io.github.graphmaquina.fixtures.Billing.PaymentTransition.VOID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.Eligibility;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.OutcomeCode;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.error.AmbiguousIdentityException;
import io.github.graphmaquina.error.AmbiguousParentException;
import io.github.graphmaquina.error.EligibilityCheckException;
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
import io.github.graphmaquina.fixtures.LibraryLog;
import io.github.graphmaquina.fixtures.LogLines;
import io.github.graphmaquina.fixtures.Refusal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.stream.Stream;
import lombok.Getter;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * An eligibility check answers for a sub-machine as execute would refuse it. A child its parent holds is answered by
 * its own edges; one no parent holds is {@code DETACHED}, and the parent's exit from its bound state is
 * {@code RESERVED_EXIT}, each with the reason execute gives and no guard. The check starts no child on an entry and
 * runs no exit when the child would end, so an eligible answer predicts neither, and it fails when two parents hold the
 * child, or a lookup breaks its contract or starts a call or a check. Each case asks one scene and executes the same
 * request on an equal one, which must refuse alike.
 */
class EligibilitySubmachineTest {

    private static final String DETACHED_REASON = "no parent holds it in DEPOSIT or BALANCE";
    private static final String ENDED_REASON = "terminal state CAPTURED accepts no transitions";
    private static final String RESERVED_REASON = "DEPOSIT_PAID leaves DEPOSIT only when its sub-machine ends";

    /** States of an escrow, which holds a payment while it is {@code HOLDING}. */
    enum EscrowStatus { OPEN, HOLDING, RELEASED, FORFEITED }

    /** Identifiers of the escrow machine. */
    enum EscrowTransition { PLACE, RELEASE, FORFEIT }

    static final MachineKey<Escrow, EscrowStatus, EscrowTransition> ESCROWS =
            MachineKey.of(Escrow.class, EscrowStatus.class, EscrowTransition.class);

    /** A second parent of payments: the booking of a payment is not the only one that can hold it. */
    static final class Escrow {

        @Getter
        private final String id;
        @Getter
        private EscrowStatus status;
        @Getter
        private final Optional<Payment> payment;

        Escrow(String id, EscrowStatus status, Optional<Payment> payment) {
            this.id = id;
            this.status = status;
            this.payment = payment;
        }

        void setStatus(EscrowStatus status) { this.status = status; }

        @Override
        public String toString() { return "Escrow#" + id; }
    }

    /** {@code escrow} holds its payment while it is HOLDING, and is the parent that the lookup of any payment finds. */
    private static GraphDefinition<Escrow, EscrowStatus, EscrowTransition> escrows(Billing billing, Escrow escrow) {
        return StateGraph.define(ESCROWS)
                .initial(EscrowStatus.OPEN).terminal(EscrowStatus.RELEASED).terminal(EscrowStatus.FORFEITED)
                .statePolicy(EscrowStatus.OPEN, "an escrow places its payment")
                .statePolicy(EscrowStatus.HOLDING, "a held payment is released or forfeited")
                .transition(EscrowTransition.PLACE, EscrowStatus.OPEN, EscrowStatus.HOLDING).endTransition()
                .transition(EscrowTransition.RELEASE, EscrowStatus.HOLDING, EscrowStatus.RELEASED).endTransition()
                .transition(EscrowTransition.FORFEIT, EscrowStatus.HOLDING, EscrowStatus.FORFEITED).endTransition()
                .submachine(EscrowStatus.HOLDING, billing.payments, (Escrow each) -> each.payment(),
                        (Payment payment) -> Optional.of(escrow))
                    .exit(PaymentStatus.CAPTURED, EscrowTransition.RELEASE)
                    .exit(PaymentStatus.DECLINED, EscrowTransition.FORFEIT)
                    .exit(PaymentStatus.VOIDED, EscrowTransition.FORFEIT)
                    .endSubmachine()
                .build();
    }

    /**
     * A booking in a state, with {@code payment} as its deposit and another payment as its balance, which the exit of
     * the deposit enters, in a registry of their own. Closing it closes the registry.
     */
    private record Scene(Billing billing, MachineRegistry registry, Booking booking, Payment payment)
            implements AutoCloseable {

        StateMachine<Booking, BookingStatus, BookingTransition> bookings() {
            return registry.of(BOOKINGS);
        }

        StateMachine<Payment, PaymentStatus, PaymentTransition> payments() {
            return registry.of(PAYMENTS);
        }

        @Override
        public void close() {
            registry.close();
        }
    }

    /** A booking {@code bookingId} in {@code parent} whose deposit is a payment in {@code child}. */
    private static Scene scene(Billing billing, String bookingId, BookingStatus parent, PaymentStatus child) {
        Payment payment = new Payment("p1", child);
        Booking booking = new Booking(bookingId, parent).attachDeposit(payment)
                .attachBalance(new Payment("p2", AUTHORIZING));
        return new Scene(billing, billing.registry(), booking, payment);
    }

    private static Scene scene(Billing billing, BookingStatus parent, PaymentStatus child) {
        return scene(billing, "b1", parent, child);
    }

    /** A booking in DEPOSIT, whose deposit is authorizing, and the lookups {@code breakIt} has changed. */
    private static Scene held(Consumer<Billing> breakIt) {
        Billing billing = new Billing();
        breakIt.accept(billing);
        return scene(billing, DEPOSIT, AUTHORIZING);
    }

    private static Scene held() {
        return held((Billing billing) -> { });
    }

    /** The deposit of a booking in DEPOSIT, whose booking {@code CANCEL} then took to CANCELLED. */
    private static Scene detached(PaymentStatus child) {
        Scene scene = scene(new Billing(), DEPOSIT, child);
        Outcome<Booking, BookingStatus, BookingTransition> cancelled =
                scene.bookings().execute(scene.booking(), CANCEL);
        assertEquals(OutcomeCode.APPLIED, cancelled.code());
        assertEquals(BookingStatus.CANCELLED, scene.booking().status());
        return scene;
    }

    /** A payment that its lookup finds no booking for, whatever the booking says. */
    private static Scene free() {
        return held((Billing billing) -> billing.bookingOf = (Payment payment) -> Optional.empty());
    }

    /** A request, asked of a scene and executed on an equal one, and the refusal expected for it. */
    private record Case(String name, Supplier<Scene> scene, Function<Scene, Optional<Refusal>> check,
                        Function<Scene, Optional<Refusal>> execute, Optional<Refusal> expected) {

        @Override
        public String toString() {
            return name;
        }
    }

    private static Case ofPayment(String name, Supplier<Scene> scene, PaymentTransition id,
                                  Optional<Refusal> expected) {
        return new Case(name, scene,
                (Scene each) -> Refusal.ofCheck(each.payments().eligibility(each.payment(), id)),
                (Scene each) -> Refusal.ofExecute(each.payments().execute(each.payment(), id)),
                expected);
    }

    private static Case ofBooking(String name, Supplier<Scene> scene, BookingTransition id,
                                  Optional<Refusal> expected) {
        return new Case(name, scene,
                (Scene each) -> Refusal.ofCheck(each.bookings().eligibility(each.booking(), id)),
                (Scene each) -> Refusal.ofExecute(each.bookings().execute(each.booking(), id)),
                expected);
    }

    static Stream<Case> cases() {
        return Stream.of(
                ofPayment("held child", EligibilitySubmachineTest::held, CAPTURE, Optional.empty()),
                ofPayment("child no binding finds a parent for", EligibilitySubmachineTest::free, CAPTURE,
                        Optional.empty()),
                ofPayment("detached child", () -> detached(AUTHORIZING), CAPTURE,
                        Refusal.of(AUTHORIZING, CAPTURE, DETACHED, DETACHED_REASON)),
                ofPayment("detached child that ended", () -> detached(CAPTURED), CAPTURE,
                        Refusal.of(CAPTURED, CAPTURE, DETACHED, DETACHED_REASON)),
                ofPayment("held child that ended", () -> scene(new Billing(), DEPOSIT, CAPTURED), CAPTURE,
                        Refusal.of(CAPTURED, CAPTURE, NO_TRANSITION, ENDED_REASON)),
                ofBooking("exit of the bound state", EligibilitySubmachineTest::held, DEPOSIT_PAID,
                        Refusal.of(DEPOSIT, DEPOSIT_PAID, RESERVED_EXIT, RESERVED_REASON)),
                ofBooking("entry to a bound state", () -> scene(new Billing(), REQUESTED, HELD), BOOK,
                        Optional.empty()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void checkIsIneligibleExactlyWhereExecuteRejects(Case each) {
        try (Scene asked = each.scene().get(); Scene run = each.scene().get()) {
            assertEquals(each.expected(), each.check().apply(asked));
            assertEquals(each.expected(), each.execute().apply(run));
        }
    }

    /** Nothing the capture would have run ran: the parent and the child stay put, and no action, guard or hook ran. */
    private static void assertNothingRan(Scene scene) {
        assertEquals(BookingStatus.DEPOSIT, scene.booking().status());
        assertEquals(AUTHORIZING, scene.payment().status());
        assertEquals(List.of(), scene.billing().hooks);
        assertEquals(List.of(), scene.billing().paymentActions.calls);
        assertEquals(List.of(), scene.billing().bookingActions.calls);
        assertEquals(0, scene.billing().settled.calls);
    }

    @Test
    void heldChildIsAnsweredByItsEdges() {
        try (Scene scene = held()) {
            Eligibility<Payment, PaymentStatus, PaymentTransition> answer =
                    scene.payments().eligibility(scene.payment(), CAPTURE);
            assertEquals(new Eligibility.Eligible<>(scene.payment(), AUTHORIZING, CAPTURE), answer);
            assertNothingRan(scene);
        }
    }

    /** The control of {@link #heldChildIsAnsweredByItsEdges}: the capture the check left alone ends the child. */
    @Test
    void executeOfTheSameCaptureRunsTheExit() {
        try (Scene scene = held()) {
            Outcome<Payment, PaymentStatus, PaymentTransition> outcome =
                    scene.payments().execute(scene.payment(), CAPTURE);
            assertEquals(OutcomeCode.APPLIED, outcome.code());
            assertEquals(BookingStatus.BALANCE, scene.booking().status());
            assertEquals(1, scene.billing().settled.calls);
            assertFalse(scene.billing().hooks.isEmpty());
        }
    }

    /**
     * An eligible capture of a held deposit says nothing of the exit that ending the child triggers: the check runs no
     * guard of the parent's exit, while the call runs it, and aborts when it refuses.
     */
    @Test
    void anEligibleCapturePredictsNothingOfTheParentsExit() {
        try (Scene scene = held((Billing billing) -> billing.settled.verdict =
                (GuardContext<Booking, BookingStatus, BookingTransition> ctx) -> GuardResult.reject("not settled"))) {
            assertEquals(new Eligibility.Eligible<>(scene.payment(), AUTHORIZING, CAPTURE),
                    scene.payments().eligibility(scene.payment(), CAPTURE));
            assertEquals(0, scene.billing().settled.calls);
            TransitionAbortedException aborted = assertThrows(TransitionAbortedException.class,
                    () -> scene.payments().execute(scene.payment(), CAPTURE));
            assertEquals(1, scene.billing().settled.calls);
            assertTrue(aborted.getMessage().contains(
                    "rejected inside the exit of DEPOSIT by guard Settled: not settled"), aborted.getMessage());
            assertEquals(AUTHORIZING, scene.payment().status());
            assertEquals(DEPOSIT, scene.booking().status());
        }
    }

    /**
     * An eligible entry to a bound state says nothing of the child it starts: the check reads no childOf of the
     * binding, while the call does, and aborts when it throws.
     */
    @Test
    void anEligibleEntryPredictsNothingOfTheChildItStarts() {
        Billing billing = new Billing();
        billing.depositOf = (Booking booking) -> {
            throw new IllegalStateException("no deposit");
        };
        try (Scene scene = scene(billing, REQUESTED, HELD)) {
            assertEquals(new Eligibility.Eligible<>(scene.booking(), REQUESTED, BOOK),
                    scene.bookings().eligibility(scene.booking(), BOOK));
            TransitionAbortedException aborted = assertThrows(TransitionAbortedException.class,
                    () -> scene.bookings().execute(scene.booking(), BOOK));
            assertInstanceOf(IllegalStateException.class, aborted.getCause());
            assertEquals(REQUESTED, scene.booking().status());
            assertEquals(HELD, scene.payment().status());
        }
    }

    @Test
    void detachedChildIsIneligible() {
        try (Scene scene = detached(AUTHORIZING)) {
            Eligibility<Payment, PaymentStatus, PaymentTransition> answer =
                    scene.payments().eligibility(scene.payment(), CAPTURE);
            assertEquals(new Eligibility.Ineligible<>(scene.payment(), AUTHORIZING, CAPTURE, DETACHED_REASON,
                    DETACHED, Optional.empty()), answer);
            assertEquals(AUTHORIZING, scene.payment().status());
            assertEquals(List.of(), scene.billing().paymentActions.calls);
        }
    }

    @Test
    void reservedExitIsIneligibleFromTheBoundState() {
        try (Scene scene = held()) {
            Eligibility<Booking, BookingStatus, BookingTransition> answer =
                    scene.bookings().eligibility(scene.booking(), DEPOSIT_PAID);
            assertEquals(new Eligibility.Ineligible<>(scene.booking(), DEPOSIT, DEPOSIT_PAID, RESERVED_REASON,
                    RESERVED_EXIT, Optional.empty()), answer);
            assertNothingRan(scene);
        }
    }

    private static List<String> started(LibraryLog log) {
        return LogLines.lines(log).stream().filter((String line) -> line.contains(" started ")).toList();
    }

    /**
     * Entering a bound state would start the child over: its payment is HELD, and execute puts it back to
     * AUTHORIZING and logs the start. The check, asked first, leaves it HELD and logs none.
     */
    private static void assertEntryStartsNoChild() {
        try (Scene scene = scene(new Billing(), REQUESTED, HELD); LibraryLog log = LibraryLog.runtime(Level.FINER)) {
            Eligibility<Booking, BookingStatus, BookingTransition> answer =
                    scene.bookings().eligibility(scene.booking(), BOOK);
            assertEquals(new Eligibility.Eligible<>(scene.booking(), REQUESTED, BOOK), answer);
            assertEquals(HELD, scene.payment().status());
            assertEquals(REQUESTED, scene.booking().status());
            assertEquals(List.of(), started(log));
            scene.bookings().execute(scene.booking(), BOOK);
            assertEquals(AUTHORIZING, scene.payment().status());
            assertEquals(1, started(log).size());
        }
    }

    /** A child that ended has no edge to run, so the check refuses it and its parent stays in the bound state. */
    private static void assertEndedChildRunsNoExit() {
        try (Scene scene = scene(new Billing(), DEPOSIT, CAPTURED)) {
            Eligibility<Payment, PaymentStatus, PaymentTransition> answer =
                    scene.payments().eligibility(scene.payment(), CAPTURE);
            assertEquals(new Eligibility.Ineligible<>(scene.payment(), CAPTURED, CAPTURE, ENDED_REASON,
                    NO_TRANSITION, Optional.empty()), answer);
            assertEquals(DEPOSIT, scene.booking().status());
            assertEquals(List.of(), scene.billing().hooks);
            assertEquals(0, scene.billing().settled.calls);
        }
    }

    @Test
    void checkStartsNoChildAndRunsNoExit() {
        assertEntryStartsNoChild();
        assertEndedChildRunsNoExit();
    }

    @Test
    void ambiguousParentFailsTheCheck() {
        Billing billing = new Billing();
        Payment payment = new Payment("p1", AUTHORIZING);
        new Booking("b1", DEPOSIT).attachDeposit(payment);
        Escrow escrow = new Escrow("e1", EscrowStatus.HOLDING, Optional.of(payment));
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(billing.bookingMachine())
                .add(billing.paymentMachine())
                .add(StateMachines.from(escrows(billing, escrow)).state(Escrow::status, Escrow::setStatus)
                        .identity(Escrow::id))
                .build()) {
            EligibilityCheckException error = assertThrows(EligibilityCheckException.class,
                    () -> registry.of(PAYMENTS).eligibility(payment, CAPTURE));
            AmbiguousParentException cause = assertInstanceOf(AmbiguousParentException.class, error.getCause());
            assertEquals(PAYMENTS, cause.child());
            assertEquals(List.of(BOOKINGS, ESCROWS), cause.parents());
            assertEquals(List.of(DEPOSIT, EscrowStatus.HOLDING), cause.nodes());
            assertEquals(Optional.of(AUTHORIZING), error.from());
            TransitionAbortedException aborted = assertThrows(TransitionAbortedException.class,
                    () -> registry.of(PAYMENTS).execute(payment, CAPTURE));
            assertInstanceOf(AmbiguousParentException.class, aborted.getCause());
            assertEquals(AUTHORIZING, payment.status());
        }
    }

    /** What a scene whose lookup or read breaks its contract makes the check fail with, if the engine names a cause. */
    private record Broken(String name, Supplier<Scene> scene, Optional<Class<? extends Throwable>> cause) {

        @Override
        public String toString() {
            return name;
        }
    }

    private static Optional<Class<? extends Throwable>> causedBy(Class<? extends Throwable> type) {
        return Optional.of(type);
    }

    static Stream<Broken> brokenReads() {
        return Stream.of(
                new Broken("parentOf returns null",
                        () -> held((Billing billing) -> billing.bookingOf = (Payment payment) -> null),
                        Optional.empty()),
                new Broken("childOf returns null",
                        () -> held((Billing billing) -> billing.depositOf = (Booking booking) -> null),
                        Optional.empty()),
                new Broken("parentOf throws", () -> held((Billing billing) -> billing.bookingOf = (Payment payment) -> {
                    throw new IllegalStateException("no booking");
                }), causedBy(IllegalStateException.class)),
                new Broken("childOf throws", () -> held((Billing billing) -> billing.depositOf = (Booking booking) -> {
                    throw new IllegalStateException("no deposit");
                }), causedBy(IllegalStateException.class)),
                new Broken("parent identity is null", () -> scene(new Billing(), null, DEPOSIT, AUTHORIZING),
                        causedBy(NullPointerException.class)),
                new Broken("parent state is null", () -> scene(new Billing(), null, AUTHORIZING),
                        causedBy(UninitializedStateException.class)),
                new Broken("childOf answers a child whose identity is null",
                        () -> held((Billing billing) -> billing.depositOf =
                                (Booking booking) -> Optional.of(new Payment(null, AUTHORIZING))),
                        causedBy(NullPointerException.class)),
                new Broken("childOf answers another instance with the child's identity",
                        () -> held((Billing billing) -> billing.depositOf =
                                (Booking booking) -> Optional.of(new Payment("p1", AUTHORIZING))),
                        causedBy(AmbiguousIdentityException.class)),
                new Broken("childOf starts a check", EligibilitySubmachineTest::reentrantChildOf,
                        causedBy(ReentrantTransitionException.class)));
    }

    /** A booking in DEPOSIT whose childOf asks for a check of its deposit before it answers it. */
    private static Scene reentrantChildOf() {
        AtomicReference<Scene> self = new AtomicReference<>();
        Scene scene = held((Billing billing) -> billing.depositOf = (Booking booking) -> {
            self.get().payments().eligibility(self.get().payment(), VOID);
            return booking.deposit();
        });
        self.set(scene);
        return scene;
    }

    /** Each fails the check with an EligibilityCheckException, where a call would abort, and writes nothing. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("brokenReads")
    void lookupThatBreaksItsContractFailsTheCheck(Broken each) {
        try (Scene scene = each.scene().get()) {
            EligibilityCheckException error = assertThrows(EligibilityCheckException.class,
                    () -> scene.payments().eligibility(scene.payment(), CAPTURE));
            assertEquals(each.cause(), Optional.ofNullable(error.getCause()).map(Throwable::getClass));
            assertEquals(Optional.of(AUTHORIZING), error.from());
            assertTrue(error.getMessage().startsWith(
                    "eligibility of CAPTURE for Payment/PaymentStatus#p1 AUTHORIZING could not be decided: "),
                    error.getMessage());
            assertEquals(AUTHORIZING, scene.payment().status());
        }
    }

    /** Each call that a lookup of a check may try to start on its thread: an execute, a reevaluation and a check. */
    static Stream<Named<BiFunction<StateMachine<Payment, PaymentStatus, PaymentTransition>, Payment, Object>>>
            nestedCalls() {
        BiFunction<StateMachine<Payment, PaymentStatus, PaymentTransition>, Payment, Object> execute =
                (StateMachine<Payment, PaymentStatus, PaymentTransition> m, Payment payment) ->
                        m.execute(payment, VOID);
        BiFunction<StateMachine<Payment, PaymentStatus, PaymentTransition>, Payment, Object> reevaluate =
                (StateMachine<Payment, PaymentStatus, PaymentTransition> m, Payment payment) -> m.reevaluate(payment);
        BiFunction<StateMachine<Payment, PaymentStatus, PaymentTransition>, Payment, Object> eligibility =
                (StateMachine<Payment, PaymentStatus, PaymentTransition> m, Payment payment) ->
                        m.eligibility(payment, VOID);
        return Stream.of(Named.of("execute", execute), Named.of("reevaluate", reevaluate),
                Named.of("eligibility", eligibility));
    }

    /** A parentOf that starts a call or a check while the check runs is refused, and the check fails with it. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("nestedCalls")
    void lookupThatStartsACallOrACheckFailsTheCheck(
            BiFunction<StateMachine<Payment, PaymentStatus, PaymentTransition>, Payment, Object> nested) {
        AtomicReference<Scene> self = new AtomicReference<>();
        AtomicReference<ReentrantTransitionException> refused = new AtomicReference<>();
        Scene scene = held((Billing billing) -> billing.bookingOf = (Payment payment) -> {
            refused.set(assertThrows(ReentrantTransitionException.class,
                    () -> nested.apply(self.get().payments(), payment)));
            throw refused.get();
        });
        self.set(scene);
        try (scene) {
            EligibilityCheckException error = assertThrows(EligibilityCheckException.class,
                    () -> scene.payments().eligibility(scene.payment(), CAPTURE));
            assertSame(refused.get(), error.getCause());
            assertEquals(AUTHORIZING, scene.payment().status());
        }
    }
}
