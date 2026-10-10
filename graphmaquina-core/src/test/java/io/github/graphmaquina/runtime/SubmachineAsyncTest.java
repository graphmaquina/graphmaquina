package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.Billing.BOOKINGS;
import static io.github.graphmaquina.fixtures.Billing.PAYMENTS;
import static io.github.graphmaquina.fixtures.Billing.RISKS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.AsyncTransitionAction;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.OutcomeCode;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.StatesWritten;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.TransitionRecord;
import io.github.graphmaquina.dsl.GraphBuilder;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.error.CommitFailedException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.fixtures.Billing.Booking;
import io.github.graphmaquina.fixtures.Billing.BookingStatus;
import io.github.graphmaquina.fixtures.Billing.BookingTransition;
import io.github.graphmaquina.fixtures.Billing.Payment;
import io.github.graphmaquina.fixtures.Billing.PaymentStatus;
import io.github.graphmaquina.fixtures.Billing.PaymentTransition;
import io.github.graphmaquina.fixtures.Billing.RiskStatus;
import io.github.graphmaquina.fixtures.Billing.RiskTransition;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Sub-machines and asynchronous work (spec R13). An exit waits under the deadline of the call that runs it, which is
 * the asyncTimeout of the machine whose call started the root, and an exit wait that times out aborts the whole call
 * with nothing written. A registry refuses a machine without asyncTimeout when an edge that its calls can run
 * implicitly is asynchronous: the exit of its binding, a failure route of such an edge, the exit that a parent's
 * terminal state selects up the chain, an exit of a sub-machine that a parent enters, or a terminal initial
 * descendant's exit that the caller's own entry starts. An asynchronous call runs
 * whole on the executor of the machine it was made on, and checks the binding when it starts running. Nothing is
 * locked: the commit's check sees a parent that another call moved only when the parent is in the commit.
 * <p>The graphs are those of the billing fixture, with asynchronous actions where a test needs them. No test sleeps:
 * a stage completes when the test completes it, and a wait that must time out has a deadline of 100 ms against one
 * of an hour.
 */
class SubmachineAsyncTest {

    /** An asynchronous action whose behaviour a test replaces: by default a stage already completed with ok. */
    static final class AsyncProbe<E, S extends Enum<S>, T extends Enum<T>> implements AsyncTransitionAction<E, S, T> {

        Function<TransitionContext<E, S, T>, CompletionStage<ActionOutcome>> behaviour =
                (TransitionContext<E, S, T> ctx) -> CompletableFuture.completedFuture(ActionOutcome.ok());

        @Override
        public CompletionStage<ActionOutcome> process(TransitionContext<E, S, T> ctx) {
            return behaviour.apply(ctx);
        }

        /** Makes every invocation return a new pending stage, which {@code invoked} receives. */
        void park(Map<String, CompletableFuture<CompletableFuture<ActionOutcome>>> invoked,
                  Function<E, String> id) {
            behaviour = (TransitionContext<E, S, T> ctx) -> {
                CompletableFuture<ActionOutcome> stage = new CompletableFuture<>();
                invoked.get(id.apply(ctx.entity())).complete(stage);
                return stage;
            };
        }
    }

    private static final Duration HOUR = Duration.ofHours(1);
    private static final Duration SHORT = Duration.ofMillis(100);
    private static final Duration GENEROUS = Duration.ofSeconds(10);
    /** A synchronous booking action that succeeds. */
    private static final TransitionAction<Booking, BookingStatus, BookingTransition> SUCCEEDS =
            (TransitionContext<Booking, BookingStatus, BookingTransition> ctx) -> ActionOutcome.ok();
    /** An asynchronous booking action whose stage has already succeeded. */
    private static final AsyncTransitionAction<Booking, BookingStatus, BookingTransition> SUCCEEDS_LATER =
            (TransitionContext<Booking, BookingStatus, BookingTransition> ctx) ->
                    CompletableFuture.completedFuture(ActionOutcome.ok());
    /** What a capture that ends the deposit runs, every part of it on the payment machine's thread. */
    private static final List<String> ON_PAYMENTS = List.of("capture@payments", "settle@payments",
            "write Payment#p1@payments", "write Booking#b1@payments", "hook Payment#p1@payments",
            "hook Booking#b1@payments");

    /** What ran, as {@code what@thread}, in order, from whichever thread ran it. */
    private final List<String> trace = new CopyOnWriteArrayList<>();
    private final AsyncProbe<Payment, PaymentStatus, PaymentTransition> hold = new AsyncProbe<>();
    private final AsyncProbe<Payment, PaymentStatus, PaymentTransition> capture = new AsyncProbe<>();
    private final AsyncProbe<Booking, BookingStatus, BookingTransition> settle = new AsyncProbe<>();
    /** The action of {@code BOOK}; by default it returns ok. */
    private Function<TransitionContext<Booking, BookingStatus, BookingTransition>, ActionOutcome> book =
            (TransitionContext<Booking, BookingStatus, BookingTransition> ctx) -> ActionOutcome.ok();

    private final GraphDefinition<Payment, PaymentStatus, PaymentTransition> payments =
            payments(Optional.empty(), true);
    private final GraphDefinition<Booking, BookingStatus, BookingTransition> bookings = bookings(payments,
            (TransitionBuilder<Booking, BookingStatus, BookingTransition> edge) -> edge.action("TakeDeposit",
                    (TransitionContext<Booking, BookingStatus, BookingTransition> ctx) -> book.apply(ctx)),
            (TransitionBuilder<Booking, BookingStatus, BookingTransition> edge) -> edge.asyncAction("Settle", settle));

    private static String thread() {
        return Thread.currentThread().getName();
    }

    /**
     * The payment graph: {@code AUTHORIZING} holds, captures or declines, and {@code HELD} captures or voids. When
     * {@code asynchronous}, the hold from {@code AUTHORIZING} runs {@link #hold} and the capture from there runs
     * {@link #capture}; otherwise both are plain moves. When {@code risks} is present, it is bound to
     * {@code AUTHORIZING}, which a cleared risk check leaves by {@code CAPTURE} and a flagged one by {@code DECLINE}.
     */
    private GraphDefinition<Payment, PaymentStatus, PaymentTransition> payments(
            Optional<GraphDefinition<Payment, RiskStatus, RiskTransition>> risks, boolean asynchronous) {
        GraphBuilder<Payment, PaymentStatus, PaymentTransition> graph = StateGraph.define(PAYMENTS)
                .initial(PaymentStatus.AUTHORIZING)
                .terminal(PaymentStatus.CAPTURED).terminal(PaymentStatus.DECLINED).terminal(PaymentStatus.VOIDED)
                .statePolicy(PaymentStatus.AUTHORIZING, "an authorization is held, captured or declined")
                .statePolicy(PaymentStatus.HELD, "a hold is captured or voided");
        TransitionBuilder<Payment, PaymentStatus, PaymentTransition> holding =
                graph.transition(PaymentTransition.HOLD, PaymentStatus.AUTHORIZING, PaymentStatus.HELD);
        TransitionBuilder<Payment, PaymentStatus, PaymentTransition> capturing =
                graph.transition(PaymentTransition.CAPTURE, PaymentStatus.AUTHORIZING, PaymentStatus.CAPTURED);
        if (asynchronous) {
            holding.asyncAction("Hold", hold);
            capturing.asyncAction("Capture", capture);
        }
        holding.endTransition();
        capturing.afterCommit("Trace", (Payment payment) -> trace.add("hook " + payment + "@" + thread()))
                .endTransition()
                .transition(PaymentTransition.DECLINE, PaymentStatus.AUTHORIZING, PaymentStatus.DECLINED)
                    .endTransition()
                .transition(PaymentTransition.CAPTURE, PaymentStatus.HELD, PaymentStatus.CAPTURED).endTransition()
                .transition(PaymentTransition.VOID, PaymentStatus.HELD, PaymentStatus.VOIDED).endTransition();
        if (risks.isPresent()) {
            graph.submachine(PaymentStatus.AUTHORIZING, risks.get())
                    .exit(RiskStatus.CLEARED, PaymentTransition.CAPTURE)
                    .exit(RiskStatus.FLAGGED, PaymentTransition.DECLINE)
                    .endSubmachine();
        }
        return graph.build();
    }

    /**
     * The booking graph: {@code BOOK} enters {@code DEPOSIT}, which binds {@code payments} through the booking's
     * deposit; its exits are {@code DEPOSIT_PAID} for a captured payment and {@code PAYMENT_FAILED} otherwise, and
     * {@code CANCEL} interrupts it. {@code DEPOSIT_PAID} enters {@code BALANCE}, which {@code BALANCE_PAID} confirms
     * and {@code PAYMENT_FAILED} cancels. When {@code balanceCheck} is present, it is bound to {@code BALANCE} through
     * the booking's balance payment: a cleared check leaves by {@code BALANCE_PAID} and a flagged one by
     * {@code PAYMENT_FAILED}. The operators complete the edges of the same names.
     */
    private GraphDefinition<Booking, BookingStatus, BookingTransition> bookings(
            GraphDefinition<Payment, PaymentStatus, PaymentTransition> payments,
            UnaryOperator<TransitionBuilder<Booking, BookingStatus, BookingTransition>> book,
            UnaryOperator<TransitionBuilder<Booking, BookingStatus, BookingTransition>> depositPaid,
            UnaryOperator<TransitionBuilder<Booking, BookingStatus, BookingTransition>> cancel,
            UnaryOperator<TransitionBuilder<Booking, BookingStatus, BookingTransition>> balancePaid,
            Optional<GraphDefinition<Payment, RiskStatus, RiskTransition>> balanceCheck) {
        GraphBuilder<Booking, BookingStatus, BookingTransition> graph = StateGraph.define(BOOKINGS)
                .initial(BookingStatus.REQUESTED)
                .terminal(BookingStatus.CONFIRMED).terminal(BookingStatus.CANCELLED)
                .statePolicy(BookingStatus.REQUESTED, "a request is booked")
                .statePolicy(BookingStatus.DEPOSIT, "a deposit is paid or cancelled")
                .statePolicy(BookingStatus.BALANCE, "a balance is paid or fails");
        book.apply(graph.transition(BookingTransition.BOOK, BookingStatus.REQUESTED, BookingStatus.DEPOSIT))
                .endTransition();
        depositPaid.apply(graph.transition(BookingTransition.DEPOSIT_PAID, BookingStatus.DEPOSIT,
                        BookingStatus.BALANCE))
                .afterCommit("Trace", (Booking booking) -> trace.add("hook " + booking + "@" + thread()))
                .endTransition();
        cancel.apply(graph.transition(BookingTransition.CANCEL, BookingStatus.DEPOSIT, BookingStatus.CANCELLED))
                .endTransition();
        balancePaid.apply(graph.transition(BookingTransition.BALANCE_PAID, BookingStatus.BALANCE,
                        BookingStatus.CONFIRMED))
                .endTransition();
        graph.transition(BookingTransition.PAYMENT_FAILED, BookingStatus.DEPOSIT, BookingStatus.CANCELLED)
                    .endTransition()
                .transition(BookingTransition.PAYMENT_FAILED, BookingStatus.BALANCE, BookingStatus.CANCELLED)
                    .endTransition()
                .submachine(BookingStatus.DEPOSIT, payments, Booking::deposit, Payment::booking)
                    .exit(PaymentStatus.CAPTURED, BookingTransition.DEPOSIT_PAID)
                    .exit(PaymentStatus.DECLINED, BookingTransition.PAYMENT_FAILED)
                    .exit(PaymentStatus.VOIDED, BookingTransition.PAYMENT_FAILED)
                    .endSubmachine();
        if (balanceCheck.isPresent()) {
            graph.submachine(BookingStatus.BALANCE, balanceCheck.get(), Booking::balance, Payment::booking)
                    .exit(RiskStatus.CLEARED, BookingTransition.BALANCE_PAID)
                    .exit(RiskStatus.FLAGGED, BookingTransition.PAYMENT_FAILED)
                    .endSubmachine();
        }
        return graph.build();
    }

    /** The booking graph with a plain {@code CANCEL} and {@code BALANCE_PAID}, and nothing bound to {@code BALANCE}. */
    private GraphDefinition<Booking, BookingStatus, BookingTransition> bookings(
            GraphDefinition<Payment, PaymentStatus, PaymentTransition> payments,
            UnaryOperator<TransitionBuilder<Booking, BookingStatus, BookingTransition>> book,
            UnaryOperator<TransitionBuilder<Booking, BookingStatus, BookingTransition>> depositPaid) {
        return bookings(payments, book, depositPaid, UnaryOperator.identity(), UnaryOperator.identity(),
                Optional.empty());
    }

    /** The booking graph over a synchronous payment graph, with a plain {@code BOOK} and the given exit. */
    private GraphDefinition<Booking, BookingStatus, BookingTransition> bookings(
            GraphDefinition<Payment, PaymentStatus, PaymentTransition> payments,
            UnaryOperator<TransitionBuilder<Booking, BookingStatus, BookingTransition>> depositPaid) {
        return bookings(payments, UnaryOperator.identity(), depositPaid);
    }

    private static GraphDefinition<Payment, RiskStatus, RiskTransition> risks() {
        return StateGraph.define(RISKS)
                .initial(RiskStatus.SCORING).terminal(RiskStatus.CLEARED).terminal(RiskStatus.FLAGGED)
                .statePolicy(RiskStatus.SCORING, "a risk score clears or flags the payment")
                .transition(RiskTransition.CLEAR, RiskStatus.SCORING, RiskStatus.CLEARED).endTransition()
                .transition(RiskTransition.FLAG, RiskStatus.SCORING, RiskStatus.FLAGGED).endTransition()
                .build();
    }

    /** A booking machine whose setter records what it writes, on which thread. */
    private StateMachines.Builder<Booking, BookingStatus, BookingTransition> bookingMachine(
            GraphDefinition<Booking, BookingStatus, BookingTransition> graph) {
        return StateMachines.from(graph).state(Booking::status, (Booking booking, BookingStatus status) -> {
            trace.add("write " + booking + "@" + thread());
            booking.setStatus(status);
        }).identity(Booking::id);
    }

    /** A payment machine whose setter records what it writes, on which thread. */
    private StateMachines.Builder<Payment, PaymentStatus, PaymentTransition> paymentMachine(
            GraphDefinition<Payment, PaymentStatus, PaymentTransition> graph) {
        return StateMachines.from(graph).state(Payment::status, (Payment payment, PaymentStatus status) -> {
            trace.add("write " + payment + "@" + thread());
            payment.setStatus(status);
        }).identity(Payment::id);
    }

    private MachineRegistry registry(Duration bookingTimeout, Duration paymentTimeout) {
        return MachineRegistry.builder()
                .add(bookingMachine(bookings).asyncTimeout(bookingTimeout))
                .add(paymentMachine(payments).asyncTimeout(paymentTimeout))
                .build();
    }

    private static List<String> described(List<TransitionRecord<?, ?, ?>> records) {
        return records.stream().map((TransitionRecord<?, ?, ?> record) -> record.entity() + ":" + record.from() + "-"
                + record.transition() + ">" + record.to() + "@" + record.depth()).toList();
    }

    /**
     * The capture ends the deposit, and the exit's action never completes. The call started on the payment, so the
     * exit waits under the payment machine's 100 ms, not under the booking machine's hour: the call aborts in ACTION,
     * nothing is written, no hook runs, and the stage is left running.
     */
    @Test
    void anExitWaitsUnderTheDeadlineOfTheChildWhoseCallEndsIt() {
        CompletableFuture<ActionOutcome> never = new CompletableFuture<>();
        settle.behaviour = (TransitionContext<Booking, BookingStatus, BookingTransition> ctx) -> never;
        Payment deposit = new Payment("p1", PaymentStatus.AUTHORIZING);
        Booking booking = new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(deposit);
        try (MachineRegistry registry = registry(HOUR, SHORT)) {
            StateMachine<Payment, PaymentStatus, PaymentTransition> machine = registry.of(PAYMENTS);
            TransitionAbortedException error = assertTimeoutPreemptively(GENEROUS,
                    () -> assertThrows(TransitionAbortedException.class,
                            () -> machine.execute(deposit, PaymentTransition.CAPTURE)));
            assertEquals(CallPhase.ACTION, error.phase());
            assertEquals(StatesWritten.NONE, error.statesWritten());
            assertInstanceOf(TimeoutException.class, error.getCause());
            assertEquals("transition aborted in Booking/BookingStatus DEPOSIT -DEPOSIT_PAID-> BALANCE: waiting for the"
                    + " action Settle timed out [route Booking/BookingStatus#b1]", error.getMessage());
            assertEquals(PaymentStatus.AUTHORIZING, deposit.status());
            assertEquals(BookingStatus.DEPOSIT, booking.status());
            assertEquals(List.of(), trace);
            assertFalse(never.isDone());
        }
    }

    /**
     * The booking's own call books into DEPOSIT, and its action captures the payment it started through a cascade.
     * The booking is in flight then, so the exit runs at the end of the booking's step, under the booking machine's
     * 100 ms, not under the payment machine's hour.
     */
    @Test
    void anExitWaitsUnderTheDeadlineOfTheParentWhoseCallCascadedTheChild() {
        settle.behaviour = (TransitionContext<Booking, BookingStatus, BookingTransition> ctx) ->
                new CompletableFuture<>();
        book = (TransitionContext<Booking, BookingStatus, BookingTransition> ctx) -> {
            ctx.cascade(PAYMENTS, ctx.entity().deposit().orElseThrow(), PaymentTransition.CAPTURE);
            return ActionOutcome.ok();
        };
        Payment deposit = new Payment("p1", PaymentStatus.AUTHORIZING);
        Booking booking = new Booking("b1", BookingStatus.REQUESTED).attachDeposit(deposit);
        try (MachineRegistry registry = registry(SHORT, HOUR)) {
            StateMachine<Booking, BookingStatus, BookingTransition> machine = registry.of(BOOKINGS);
            TransitionAbortedException error = assertTimeoutPreemptively(GENEROUS,
                    () -> assertThrows(TransitionAbortedException.class,
                            () -> machine.execute(booking, BookingTransition.BOOK)));
            assertEquals(CallPhase.ACTION, error.phase());
            assertEquals(StatesWritten.NONE, error.statesWritten());
            assertInstanceOf(TimeoutException.class, error.getCause());
            assertEquals("transition aborted in Booking/BookingStatus DEPOSIT -DEPOSIT_PAID-> BALANCE: waiting for the"
                    + " action Settle timed out [route Booking/BookingStatus#b1]", error.getMessage());
            assertEquals(PaymentStatus.AUTHORIZING, deposit.status());
            assertEquals(BookingStatus.REQUESTED, booking.status());
        }
    }

    /**
     * Captures a deposit through {@code call} on a payment machine whose executor is the thread {@code payments}, the
     * booking machine's being the thread {@code bookings}, and returns what ran where.
     */
    private List<String> captureOnNamedThreads(BiFunction<StateMachine<Payment, PaymentStatus, PaymentTransition>,
            Payment, CompletableFuture<Outcome<Payment, PaymentStatus, PaymentTransition>>> call) throws Exception {
        capture.behaviour = (TransitionContext<Payment, PaymentStatus, PaymentTransition> ctx) -> {
            trace.add("capture@" + thread());
            return CompletableFuture.completedFuture(ActionOutcome.ok());
        };
        settle.behaviour = (TransitionContext<Booking, BookingStatus, BookingTransition> ctx) -> {
            trace.add("settle@" + thread());
            return CompletableFuture.completedFuture(ActionOutcome.ok());
        };
        Payment deposit = new Payment("p1", PaymentStatus.AUTHORIZING);
        Booking booking = new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(deposit);
        ExecutorService paymentThread =
                Executors.newSingleThreadExecutor((Runnable task) -> new Thread(task, "payments"));
        ExecutorService bookingThread =
                Executors.newSingleThreadExecutor((Runnable task) -> new Thread(task, "bookings"));
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(bookingMachine(bookings).asyncTimeout(HOUR).asyncExecutor(bookingThread))
                .add(paymentMachine(payments).asyncTimeout(HOUR).asyncExecutor(paymentThread))
                .build()) {
            Outcome<Payment, PaymentStatus, PaymentTransition> outcome =
                    call.apply(registry.of(PAYMENTS), deposit).get(GENEROUS.toSeconds(), TimeUnit.SECONDS);
            assertEquals(List.of("Payment#p1:AUTHORIZING-CAPTURE>CAPTURED@0",
                    "Booking#b1:DEPOSIT-DEPOSIT_PAID>BALANCE@1"), described(outcome.records()));
            assertEquals(BookingStatus.BALANCE, booking.status());
            return List.copyOf(trace);
        } finally {
            paymentThread.shutdownNow();
            bookingThread.shutdownNow();
        }
    }

    /**
     * {@code async().execute(...)} runs the child's step, the parent's exit, the commit of both and the hooks of both
     * on the executor of the machine it was made on, the payment's, and nothing on the booking's.
     */
    @Test
    void asyncExecuteRunsTheWholeCallOnTheExecutorOfTheMachineItWasMadeOn() throws Exception {
        assertEquals(ON_PAYMENTS, captureOnNamedThreads(
                (StateMachine<Payment, PaymentStatus, PaymentTransition> machine, Payment deposit) ->
                        machine.async().execute(deposit, PaymentTransition.CAPTURE)));
    }

    /** {@code supplyAsync}, which a generated facade's {@code async(...)} goes through, does the same. */
    @Test
    void supplyAsyncRunsTheWholeCallOnTheExecutorOfTheMachineItWasMadeOn() throws Exception {
        assertEquals(ON_PAYMENTS, captureOnNamedThreads(
                (StateMachine<Payment, PaymentStatus, PaymentTransition> machine, Payment deposit) ->
                        machine.supplyAsync(() -> machine.execute(deposit, PaymentTransition.CAPTURE))));
    }

    /**
     * An asynchronous call checks the binding when it starts running, not when it is submitted: the booking is in
     * DEPOSIT when the capture is submitted and cancelled by the time the executor runs it, so the capture is
     * rejected as DETACHED and the payment does not move.
     */
    @Test
    void anAsynchronousCallChecksTheBindingWhenItStartsRunning() {
        List<Runnable> queued = new ArrayList<>();
        Executor held = queued::add;
        Payment deposit = new Payment("p1", PaymentStatus.AUTHORIZING);
        Booking booking = new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(deposit);
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(bookingMachine(bookings).asyncTimeout(HOUR))
                .add(paymentMachine(payments).asyncTimeout(HOUR).asyncExecutor(held))
                .build()) {
            CompletableFuture<Outcome<Payment, PaymentStatus, PaymentTransition>> submitted =
                    registry.of(PAYMENTS).async().execute(deposit, PaymentTransition.CAPTURE);
            assertEquals(1, queued.size());
            assertEquals(OutcomeCode.APPLIED, registry.of(BOOKINGS).execute(booking, BookingTransition.CANCEL).code());
            queued.get(0).run();
            Outcome<Payment, PaymentStatus, PaymentTransition> outcome = submitted.join();
            assertEquals(OutcomeCode.DETACHED, outcome.code());
            assertEquals(Optional.of("no parent holds it in DEPOSIT"), outcome.explanation());
            assertEquals(PaymentStatus.AUTHORIZING, deposit.status());
            assertEquals(BookingStatus.CANCELLED, booking.status());
        }
    }

    /**
     * Two captures of two deposits, each bound to its own booking, wait at once. Completed in the reverse order of
     * their calls, each commits with the exit of its own booking, and the other booking does not move until its own
     * capture completes.
     */
    @Test
    void twoChildCallsCompletedInReverseOrderEachExitTheirOwnParent() throws Exception {
        Map<String, CompletableFuture<CompletableFuture<ActionOutcome>>> invoked = new ConcurrentHashMap<>(
                Map.of("p1", new CompletableFuture<>(), "p2", new CompletableFuture<>()));
        capture.park(invoked, Payment::id);
        Payment first = new Payment("p1", PaymentStatus.AUTHORIZING);
        Booking firstBooking = new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(first);
        Payment second = new Payment("p2", PaymentStatus.AUTHORIZING);
        Booking secondBooking = new Booking("b2", BookingStatus.DEPOSIT).attachDeposit(second);
        try (MachineRegistry registry = registry(HOUR, HOUR)) {
            StateMachine<Payment, PaymentStatus, PaymentTransition> machine = registry.of(PAYMENTS);
            CompletableFuture<Outcome<Payment, PaymentStatus, PaymentTransition>> firstCall =
                    machine.async().execute(first, PaymentTransition.CAPTURE);
            CompletableFuture<Outcome<Payment, PaymentStatus, PaymentTransition>> secondCall =
                    machine.async().execute(second, PaymentTransition.CAPTURE);
            CompletableFuture<ActionOutcome> firstStage = invoked.get("p1").get(10, TimeUnit.SECONDS);
            CompletableFuture<ActionOutcome> secondStage = invoked.get("p2").get(10, TimeUnit.SECONDS);

            secondStage.complete(ActionOutcome.ok());
            assertEquals(List.of("Payment#p2:AUTHORIZING-CAPTURE>CAPTURED@0",
                            "Booking#b2:DEPOSIT-DEPOSIT_PAID>BALANCE@1"),
                    described(secondCall.get(10, TimeUnit.SECONDS).records()));
            assertEquals(BookingStatus.BALANCE, secondBooking.status());
            assertFalse(firstCall.isDone());
            assertEquals(PaymentStatus.AUTHORIZING, first.status());
            assertEquals(BookingStatus.DEPOSIT, firstBooking.status());

            firstStage.complete(ActionOutcome.ok());
            assertEquals(List.of("Payment#p1:AUTHORIZING-CAPTURE>CAPTURED@0",
                            "Booking#b1:DEPOSIT-DEPOSIT_PAID>BALANCE@1"),
                    described(firstCall.get(10, TimeUnit.SECONDS).records()));
            assertEquals(BookingStatus.BALANCE, firstBooking.status());
            assertEquals(PaymentStatus.CAPTURED, second.status());
        }
    }

    /**
     * While the exit's action waits, another call cancels the booking. The exit puts the booking in the capture's
     * commit, so the commit's check sees that the booking moved and writes nothing.
     */
    @Test
    void aParentThatAnotherCallMovesWhileItsExitWaitsFailsTheCommitCheck() throws Exception {
        Map<String, CompletableFuture<CompletableFuture<ActionOutcome>>> invoked =
                new ConcurrentHashMap<>(Map.of("b1", new CompletableFuture<>()));
        settle.park(invoked, Booking::id);
        Payment deposit = new Payment("p1", PaymentStatus.AUTHORIZING);
        Booking booking = new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(deposit);
        try (MachineRegistry registry = registry(HOUR, HOUR)) {
            CompletableFuture<Outcome<Payment, PaymentStatus, PaymentTransition>> call =
                    registry.of(PAYMENTS).async().execute(deposit, PaymentTransition.CAPTURE);
            CompletableFuture<ActionOutcome> stage = invoked.get("b1").get(10, TimeUnit.SECONDS);
            assertEquals(OutcomeCode.APPLIED, registry.of(BOOKINGS).execute(booking, BookingTransition.CANCEL).code());
            stage.complete(ActionOutcome.ok());
            ExecutionException error = assertThrows(ExecutionException.class,
                    () -> call.get(10, TimeUnit.SECONDS));
            CommitFailedException failure = assertInstanceOf(CommitFailedException.class, error.getCause());
            assertEquals(StatesWritten.NONE, failure.statesWritten());
            assertEquals(PaymentStatus.AUTHORIZING, deposit.status());
            assertEquals(BookingStatus.CANCELLED, booking.status());
        }
    }

    /**
     * While the capture waits, another call cancels the booking and, in that edge's action, makes another payment its
     * deposit. The capture still ends the payment the binding held when its step began, so the exit runs from DEPOSIT
     * and puts the booking in the commit, whose check sees that the booking moved and writes nothing.
     */
    @Test
    void aParentThatAnotherCallMovesAndReassignsWhileItsChildWaitsFailsTheCommitCheck() throws Exception {
        Map<String, CompletableFuture<CompletableFuture<ActionOutcome>>> invoked =
                new ConcurrentHashMap<>(Map.of("p1", new CompletableFuture<>()));
        capture.park(invoked, Payment::id);
        Payment deposit = new Payment("p1", PaymentStatus.AUTHORIZING);
        Payment replacement = new Payment("p2", PaymentStatus.AUTHORIZING);
        Booking booking = new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(deposit);
        GraphDefinition<Booking, BookingStatus, BookingTransition> reassigning = bookings(payments,
                UnaryOperator.identity(), UnaryOperator.identity(),
                (TransitionBuilder<Booking, BookingStatus, BookingTransition> edge) -> edge.action("Reassign",
                        (TransitionContext<Booking, BookingStatus, BookingTransition> ctx) -> {
                            ctx.entity().attachDeposit(replacement);
                            return ActionOutcome.ok();
                        }),
                UnaryOperator.identity(), Optional.empty());
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(bookingMachine(reassigning).asyncTimeout(HOUR))
                .add(paymentMachine(payments).asyncTimeout(HOUR))
                .build()) {
            CompletableFuture<Outcome<Payment, PaymentStatus, PaymentTransition>> call =
                    registry.of(PAYMENTS).async().execute(deposit, PaymentTransition.CAPTURE);
            CompletableFuture<ActionOutcome> stage = invoked.get("p1").get(10, TimeUnit.SECONDS);
            assertEquals(OutcomeCode.APPLIED, registry.of(BOOKINGS).execute(booking, BookingTransition.CANCEL).code());
            stage.complete(ActionOutcome.ok());
            ExecutionException error = assertThrows(ExecutionException.class,
                    () -> call.get(10, TimeUnit.SECONDS));
            CommitFailedException failure = assertInstanceOf(CommitFailedException.class, error.getCause());
            assertEquals(StatesWritten.NONE, failure.statesWritten());
            assertEquals(PaymentStatus.AUTHORIZING, deposit.status());
            assertEquals(BookingStatus.CANCELLED, booking.status());
        }
    }

    /**
     * While a hold, which ends no sub-machine, waits, another call cancels the booking. The booking is not in the
     * hold's commit, so nothing sees that it moved: the hold commits, and the application has to serialize the calls
     * of one aggregate.
     */
    @Test
    void aParentThatAnotherCallMovesWhileItsChildMovesToAStateThatIsNotTerminalGoesUnnoticed() throws Exception {
        Map<String, CompletableFuture<CompletableFuture<ActionOutcome>>> invoked =
                new ConcurrentHashMap<>(Map.of("p1", new CompletableFuture<>()));
        hold.park(invoked, Payment::id);
        Payment deposit = new Payment("p1", PaymentStatus.AUTHORIZING);
        Booking booking = new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(deposit);
        try (MachineRegistry registry = registry(HOUR, HOUR)) {
            CompletableFuture<Outcome<Payment, PaymentStatus, PaymentTransition>> call =
                    registry.of(PAYMENTS).async().execute(deposit, PaymentTransition.HOLD);
            CompletableFuture<ActionOutcome> stage = invoked.get("p1").get(10, TimeUnit.SECONDS);
            assertEquals(OutcomeCode.APPLIED, registry.of(BOOKINGS).execute(booking, BookingTransition.CANCEL).code());
            stage.complete(ActionOutcome.ok());
            assertEquals(OutcomeCode.APPLIED, call.get(10, TimeUnit.SECONDS).code());
            assertEquals(PaymentStatus.HELD, deposit.status());
            assertEquals(BookingStatus.CANCELLED, booking.status());
        }
    }

    /**
     * The payment graph is synchronous, but the exit DEPOSIT_PAID of the binding in which it is the child has an
     * asynchronous action, so the payment machine needs an asyncTimeout. The registry refuses it before it consumes
     * any builder: the same builders build once the payment machine declares one.
     */
    @Test
    void aChildWhoseExitIsAsynchronousMustDeclareAsyncTimeout() {
        GraphDefinition<Payment, PaymentStatus, PaymentTransition> plain = payments(Optional.empty(), false);
        StateMachines.Builder<Payment, PaymentStatus, PaymentTransition> payment = paymentMachine(plain);
        MachineRegistry.Builder builder = MachineRegistry.builder()
                .add(bookingMachine(bookings(plain, (TransitionBuilder<Booking, BookingStatus, BookingTransition> edge)
                        -> edge.asyncAction("Settle", settle))).asyncTimeout(HOUR))
                .add(payment);
        IllegalStateException error = assertThrows(IllegalStateException.class, builder::build);
        assertEquals("the machine Payment/PaymentStatus can run the asynchronous edge Booking/BookingStatus.DEPOSIT"
                + " -DEPOSIT_PAID-> implicitly and does not declare asyncTimeout(...)", error.getMessage());
        payment.asyncTimeout(HOUR);
        try (MachineRegistry registry = builder.build()) {
            assertEquals(Set.of(BOOKINGS, PAYMENTS), registry.keys());
        }
    }

    /** An asynchronous guard makes an exit asynchronous too, whatever its action. */
    @Test
    void anAsynchronousGuardMakesTheExitAsynchronous() {
        GraphDefinition<Payment, PaymentStatus, PaymentTransition> plain = payments(Optional.empty(), false);
        MachineRegistry.Builder builder = MachineRegistry.builder()
                .add(bookingMachine(bookings(plain, (TransitionBuilder<Booking, BookingStatus, BookingTransition> edge)
                        -> edge.asyncGuard("Settled", (GuardContext<Booking, BookingStatus, BookingTransition> ctx)
                                -> CompletableFuture.completedFuture(GuardResult.pass())))).asyncTimeout(HOUR))
                .add(paymentMachine(plain));
        IllegalStateException error = assertThrows(IllegalStateException.class, builder::build);
        assertEquals("the machine Payment/PaymentStatus can run the asynchronous edge Booking/BookingStatus.DEPOSIT"
                + " -DEPOSIT_PAID-> implicitly and does not declare asyncTimeout(...)", error.getMessage());
    }

    /**
     * The risk check is bound to the payment's AUTHORIZING, whose exits are synchronous, and the payment to the
     * booking's DEPOSIT, whose exit DEPOSIT_PAID is not. A risk check that clears a payment captures it, and a
     * captured payment selects DEPOSIT_PAID one level up, so the risk machine needs an asyncTimeout as well.
     */
    @Test
    void aGrandchildMustDeclareAsyncTimeoutWhenAnExitUpTheChainIsAsynchronous() {
        GraphDefinition<Payment, RiskStatus, RiskTransition> risks = risks();
        GraphDefinition<Payment, PaymentStatus, PaymentTransition> checked = payments(Optional.of(risks), false);
        MachineRegistry.Builder builder = MachineRegistry.builder()
                .add(bookingMachine(bookings(checked, (TransitionBuilder<Booking, BookingStatus, BookingTransition>
                        edge) -> edge.asyncAction("Settle", settle))).asyncTimeout(HOUR))
                .add(paymentMachine(checked).asyncTimeout(HOUR))
                .add(StateMachines.from(risks).state(Payment::risk, Payment::setRisk).identity(Payment::id));
        IllegalStateException error = assertThrows(IllegalStateException.class, builder::build);
        assertEquals("the machine Payment/RiskStatus can run the asynchronous edge Booking/BookingStatus.DEPOSIT"
                + " -DEPOSIT_PAID-> implicitly and does not declare asyncTimeout(...)", error.getMessage());
    }

    /**
     * The exit DEPOSIT_PAID has a synchronous action, whose failure takes CANCEL, which is asynchronous. A capture
     * that ends the deposit can fail its exit into that route, so the payment machine needs an asyncTimeout, and the
     * registry names the route by its own origin and identifier.
     */
    @Test
    void anAsynchronousFailureRouteOfAnExitCounts() {
        GraphDefinition<Payment, PaymentStatus, PaymentTransition> plain = payments(Optional.empty(), false);
        MachineRegistry.Builder builder = MachineRegistry.builder()
                .add(bookingMachine(bookings(plain, UnaryOperator.identity(),
                        (TransitionBuilder<Booking, BookingStatus, BookingTransition> edge) ->
                                edge.action("Settle", SUCCEEDS).onFailure(BookingTransition.CANCEL),
                        (TransitionBuilder<Booking, BookingStatus, BookingTransition> edge) ->
                                edge.asyncAction("CancelBooking", SUCCEEDS_LATER),
                        UnaryOperator.identity(), Optional.empty())).asyncTimeout(HOUR))
                .add(paymentMachine(plain));
        IllegalStateException error = assertThrows(IllegalStateException.class, builder::build);
        assertEquals("the machine Payment/PaymentStatus can run the asynchronous edge Booking/BookingStatus.DEPOSIT"
                + " -CANCEL-> implicitly and does not declare asyncTimeout(...)", error.getMessage());
    }

    /**
     * The exit DEPOSIT_PAID is synchronous and enters BALANCE, where the risk check of the balance payment is bound.
     * Its exit BALANCE_PAID is asynchronous, and the check may stand in CLEARED as soon as it starts, so the capture
     * that ends the deposit can run BALANCE_PAID too: the payment machine needs an asyncTimeout.
     */
    @Test
    void anExitIntoAnotherBoundStateCountsTheExitsOfThatState() {
        GraphDefinition<Payment, PaymentStatus, PaymentTransition> plain = payments(Optional.empty(), false);
        GraphDefinition<Payment, RiskStatus, RiskTransition> risks = risks();
        MachineRegistry.Builder builder = MachineRegistry.builder()
                .add(bookingMachine(bookings(plain, UnaryOperator.identity(), UnaryOperator.identity(),
                        UnaryOperator.identity(),
                        (TransitionBuilder<Booking, BookingStatus, BookingTransition> edge) ->
                                edge.asyncAction("Confirm", SUCCEEDS_LATER),
                        Optional.of(risks))).asyncTimeout(HOUR))
                .add(paymentMachine(plain))
                .add(StateMachines.from(risks).state(Payment::risk, Payment::setRisk).identity(Payment::id)
                        .asyncTimeout(HOUR));
        IllegalStateException error = assertThrows(IllegalStateException.class, builder::build);
        assertEquals("the machine Payment/PaymentStatus can run the asynchronous edge Booking/BookingStatus.BALANCE"
                + " -BALANCE_PAID-> implicitly and does not declare asyncTimeout(...)", error.getMessage());
    }

    /**
     * Everything that a call of the payment or the risk machine can run implicitly is synchronous: the exits of
     * DEPOSIT and AUTHORIZING, the route CANCEL of DEPOSIT_PAID, and the exits of BALANCE, which DEPOSIT_PAID enters.
     * The only asynchronous edge is BOOK, which no exit, route or entry runs, so neither machine declares an
     * asyncTimeout and the registry builds.
     */
    @Test
    void implicitWorkThatIsAllSynchronousAsksNoAsyncTimeout() {
        GraphDefinition<Payment, RiskStatus, RiskTransition> risks = risks();
        GraphDefinition<Payment, PaymentStatus, PaymentTransition> checked = payments(Optional.of(risks), false);
        GraphDefinition<Booking, BookingStatus, BookingTransition> asyncBooking = bookings(checked,
                (TransitionBuilder<Booking, BookingStatus, BookingTransition> edge) ->
                        edge.asyncAction("TakeDeposit", SUCCEEDS_LATER),
                (TransitionBuilder<Booking, BookingStatus, BookingTransition> edge) ->
                        edge.action("Settle", SUCCEEDS).onFailure(BookingTransition.CANCEL),
                UnaryOperator.identity(), UnaryOperator.identity(), Optional.of(risks));
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(bookingMachine(asyncBooking).asyncTimeout(HOUR))
                .add(paymentMachine(checked))
                .add(StateMachines.from(risks).state(Payment::risk, Payment::setRisk).identity(Payment::id))
                .build()) {
            assertEquals(Set.of(BOOKINGS, PAYMENTS, RISKS), registry.keys());
        }
    }

    /** The root enters a child whose initial node holds the completed leaf. */
    enum ParentState { OPEN, BOUND, DONE }

    /** Root entry and its reserved completion identifier. */
    enum ParentId { ENTER, FINISH }

    /** The child waits for its initial leaf, then completes. */
    enum ChildState { WAIT, DONE }

    /** The child's asynchronous exit selected by the leaf. */
    enum ChildId { FINISH }

    /** A leaf that completes as soon as it starts. */
    enum LeafState { DONE }

    /** The leaf has no declared edge. */
    enum LeafId { UNUSED }

    /** One aggregate with three independent state fields and a count of actual setter calls. */
    static final class Nested {
        private ParentState parent = ParentState.OPEN;
        private ChildState child = ChildState.DONE;
        private LeafState leaf = LeafState.DONE;
        private int writes;
    }

    private static final MachineKey<Nested, ParentState, ParentId> PARENTS =
            MachineKey.of(Nested.class, ParentState.class, ParentId.class);
    private static final MachineKey<Nested, ChildState, ChildId> CHILDREN =
            MachineKey.of(Nested.class, ChildState.class, ChildId.class);
    private static final MachineKey<Nested, LeafState, LeafId> LEAVES =
            MachineKey.of(Nested.class, LeafState.class, LeafId.class);

    /** The pending child exit and the reusable registry builders of the pure DSL three-level graph. */
    private record InitialCompletion(MachineRegistry.Builder registry,
                                      StateMachines.Builder<Nested, ParentState, ParentId> root,
                                      CompletableFuture<ActionOutcome> stage) { }

    private static InitialCompletion initialCompletion() {
        CompletableFuture<ActionOutcome> stage = new CompletableFuture<>();
        GraphDefinition<Nested, LeafState, LeafId> leaf = StateGraph.define(LEAVES)
                .initial(LeafState.DONE).terminal(LeafState.DONE).build();
        GraphDefinition<Nested, ChildState, ChildId> child = StateGraph.define(CHILDREN)
                .initial(ChildState.WAIT).terminal(ChildState.DONE)
                .statePolicy(ChildState.WAIT, "wait for the initial leaf")
                .transition(ChildId.FINISH, ChildState.WAIT, ChildState.DONE)
                    .asyncAction("FinishChild", (TransitionContext<Nested, ChildState, ChildId> ctx) -> stage)
                    .endTransition()
                .submachine(ChildState.WAIT, leaf).exit(LeafState.DONE, ChildId.FINISH).endSubmachine().build();
        GraphDefinition<Nested, ParentState, ParentId> parent = StateGraph.define(PARENTS)
                .initial(ParentState.OPEN).terminal(ParentState.DONE)
                .statePolicy(ParentState.OPEN, "enter the child")
                .statePolicy(ParentState.BOUND, "wait for the child")
                .transition(ParentId.ENTER, ParentState.OPEN, ParentState.BOUND).endTransition()
                .transition(ParentId.FINISH, ParentState.BOUND, ParentState.DONE).endTransition()
                .submachine(ParentState.BOUND, child).exit(ChildState.DONE, ParentId.FINISH).endSubmachine().build();
        StateMachines.Builder<Nested, ParentState, ParentId> root = StateMachines.from(parent)
                .state((Nested entity) -> entity.parent, (Nested entity, ParentState state) -> {
                    entity.writes++;
                    entity.parent = state;
                }).identity((Nested entity) -> "one");
        MachineRegistry.Builder registry = MachineRegistry.builder().add(root)
                .add(StateMachines.from(child).state((Nested entity) -> entity.child,
                        (Nested entity, ChildState state) -> {
                            entity.writes++;
                            entity.child = state;
                        }).identity((Nested entity) -> "one").asyncTimeout(HOUR))
                .add(StateMachines.from(leaf).state((Nested entity) -> entity.leaf,
                        (Nested entity, LeafState state) -> {
                            entity.writes++;
                            entity.leaf = state;
                        }).identity((Nested entity) -> "one").asyncTimeout(HOUR));
        return new InitialCompletion(registry, root, stage);
    }

    /** A synchronous root's own entry runs the initial descendant's exit implicitly and requires a root timeout. */
    @Test
    void anOwnEntryWithATerminalInitialDescendantRequiresTheRootsTimeout() {
        InitialCompletion completion = initialCompletion();
        IllegalStateException error = assertTimeoutPreemptively(GENEROUS,
                () -> assertThrows(IllegalStateException.class, completion.registry()::build));
        assertEquals("the machine Nested/ParentState can run the asynchronous edge Nested/ChildState.WAIT"
                + " -FINISH-> implicitly and does not declare asyncTimeout(...)", error.getMessage());
        completion.root().asyncTimeout(SHORT);
        try (MachineRegistry registry = completion.registry().build()) {
            assertEquals(Set.of(PARENTS, CHILDREN, LEAVES), registry.keys());
        }
    }

    /** The descendant exit uses the entering root's short deadline, writes nothing, and leaves the user stage alive. */
    @Test
    void anInitialDescendantExitWaitsUnderTheEnteringRootsDeadline() {
        InitialCompletion completion = initialCompletion();
        completion.root().asyncTimeout(SHORT);
        Nested entity = new Nested();
        try (MachineRegistry registry = completion.registry().build()) {
            TransitionAbortedException error = assertTimeoutPreemptively(GENEROUS,
                    () -> assertThrows(TransitionAbortedException.class,
                            () -> registry.of(PARENTS).execute(entity, ParentId.ENTER)));
            assertEquals(CallPhase.ACTION, error.phase());
            assertEquals(StatesWritten.NONE, error.statesWritten());
            assertInstanceOf(TimeoutException.class, error.getCause());
            assertEquals("transition aborted in Nested/ChildState WAIT -FINISH-> DONE: waiting for the action"
                    + " FinishChild timed out [route Nested/ChildState#one]", error.getMessage());
            assertEquals(ParentState.OPEN, entity.parent);
            assertEquals(ChildState.DONE, entity.child);
            assertEquals(LeafState.DONE, entity.leaf);
            assertEquals(0, entity.writes);
            assertFalse(completion.stage().isDone());
            assertFalse(completion.stage().isCancelled());
        }
    }

    /** The first parent uses the same simple type names as the second parent but distinct actual classes. */
    static final class First {
        /** The first parent's bound and completed states. */
        enum State { BOUND, DONE }

        /** The first parent's synchronous exit. */
        enum Id { FINISH }

        /** The first parent's entity. */
        static final class Entity {
            private State state = State.BOUND;
        }
    }

    /** A distinct parent whose diagnostic labels collide with the first parent's. */
    static final class Second {
        /** The second parent's bound and completed states. */
        enum State { BOUND, DONE }

        /** The second parent's asynchronous exit. */
        enum Id { FINISH }

        /** The second parent's entity. */
        static final class Entity {
            private State state = State.BOUND;
        }
    }

    /** One child graph bound by both same-label parents. */
    static final class Child {
        private ChildState state = ChildState.WAIT;
    }

    /** Diagnostic collisions must not hide the later asynchronous exit or consume builders on refusal. */
    @Test
    void distinctMachineKeysWithEqualDiagnosticsRetainEveryImplicitExit() {
        MachineKey<Child, ChildState, ChildId> childKey =
                MachineKey.of(Child.class, ChildState.class, ChildId.class);
        GraphDefinition<Child, ChildState, ChildId> child = StateGraph.define(childKey)
                .initial(ChildState.WAIT).terminal(ChildState.DONE)
                .statePolicy(ChildState.WAIT, "finish the child")
                .transition(ChildId.FINISH, ChildState.WAIT, ChildState.DONE).endTransition().build();
        MachineKey<First.Entity, First.State, First.Id> firstKey =
                MachineKey.of(First.Entity.class, First.State.class, First.Id.class);
        MachineKey<Second.Entity, Second.State, Second.Id> secondKey =
                MachineKey.of(Second.Entity.class, Second.State.class, Second.Id.class);
        assertFalse(firstKey.equals(secondKey));
        assertEquals(firstKey.toString(), secondKey.toString());
        assertEquals(firstKey + "." + First.State.BOUND + " -" + First.Id.FINISH + "->",
                secondKey + "." + Second.State.BOUND + " -" + Second.Id.FINISH + "->");
        GraphDefinition<First.Entity, First.State, First.Id> first = StateGraph.define(firstKey)
                .initial(First.State.BOUND).terminal(First.State.DONE)
                .statePolicy(First.State.BOUND, "wait for the child")
                .transition(First.Id.FINISH, First.State.BOUND, First.State.DONE).endTransition()
                .submachine(First.State.BOUND, child, (First.Entity entity) -> Optional.<Child>empty(),
                        (Child entity) -> Optional.<First.Entity>empty())
                    .exit(ChildState.DONE, First.Id.FINISH).endSubmachine().build();
        GraphDefinition<Second.Entity, Second.State, Second.Id> second = StateGraph.define(secondKey)
                .initial(Second.State.BOUND).terminal(Second.State.DONE)
                .statePolicy(Second.State.BOUND, "wait for the child")
                .transition(Second.Id.FINISH, Second.State.BOUND, Second.State.DONE)
                    .asyncAction("Finish", (TransitionContext<Second.Entity, Second.State, Second.Id> ctx) ->
                            CompletableFuture.completedFuture(ActionOutcome.ok()))
                    .endTransition()
                .submachine(Second.State.BOUND, child, (Second.Entity entity) -> Optional.<Child>empty(),
                        (Child entity) -> Optional.<Second.Entity>empty())
                    .exit(ChildState.DONE, Second.Id.FINISH).endSubmachine().build();
        StateMachines.Builder<Child, ChildState, ChildId> childMachine = StateMachines.from(child)
                .state((Child entity) -> entity.state, (Child entity, ChildState state) -> entity.state = state)
                .identity((Child entity) -> "child");
        MachineRegistry.Builder builder = MachineRegistry.builder()
                .add(StateMachines.from(first).state((First.Entity entity) -> entity.state,
                        (First.Entity entity, First.State state) -> entity.state = state)
                        .identity((First.Entity entity) -> "first"))
                .add(StateMachines.from(second).state((Second.Entity entity) -> entity.state,
                        (Second.Entity entity, Second.State state) -> entity.state = state)
                        .identity((Second.Entity entity) -> "second").asyncTimeout(HOUR))
                .add(childMachine);
        IllegalStateException error = assertTimeoutPreemptively(GENEROUS,
                () -> assertThrows(IllegalStateException.class, builder::build));
        assertEquals("the machine Child/ChildState can run the asynchronous edge Entity/State.BOUND -FINISH->"
                + " implicitly and does not declare asyncTimeout(...)", error.getMessage());
        childMachine.asyncTimeout(HOUR);
        try (MachineRegistry registry = builder.build()) {
            assertEquals(Set.of(firstKey, secondKey, childKey), registry.keys());
        }
    }
}
