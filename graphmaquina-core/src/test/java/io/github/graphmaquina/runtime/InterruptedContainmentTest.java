package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.Billing.BOOKINGS;
import static io.github.graphmaquina.fixtures.Billing.PAYMENTS;
import static io.github.graphmaquina.fixtures.DocGraphs.docs;
import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.ARCHIVE;
import static io.github.graphmaquina.fixtures.DocTransition.DISCARD;
import static io.github.graphmaquina.fixtures.DocTransition.PUBLISH;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.FlowKey;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.MachineCall;
import io.github.graphmaquina.api.MachineObserver;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.OutcomeCode;
import io.github.graphmaquina.api.PeerView;
import io.github.graphmaquina.api.Reaction;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.error.CallFailedException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.fixtures.Billing;
import io.github.graphmaquina.fixtures.Billing.Booking;
import io.github.graphmaquina.fixtures.Billing.BookingStatus;
import io.github.graphmaquina.fixtures.Billing.BookingTransition;
import io.github.graphmaquina.fixtures.Billing.Payment;
import io.github.graphmaquina.fixtures.Billing.PaymentStatus;
import io.github.graphmaquina.fixtures.Billing.PaymentTransition;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import io.github.graphmaquina.fixtures.LibraryLog;
import io.github.graphmaquina.fixtures.Sneaky;
import io.github.graphmaquina.internal.Texts;
import java.lang.reflect.UndeclaredThrowableException;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Where the library contains what a callback or a backend throws instead of letting it through, an
 * {@link InterruptedException} it contains interrupts the thread again: a blocking call that throws it clears the
 * thread's interrupt status, which would otherwise be lost, and code that relies on it, such as a cancelled task, would
 * go on as if the thread were never interrupted.
 */
class InterruptedContainmentTest {

    /** What a blocking call does on an interrupted thread: it clears the interrupt and throws, here undeclared. */
    private static void block() {
        if (Thread.interrupted()) {
            throw Sneaky.raise(new InterruptedException("interrupted while blocked"));
        }
    }

    /** {@code body} run on an interrupted thread, then whether the thread is still interrupted, cleared afterwards. */
    private static <T> Interrupted<T> onInterruptedThread(Supplier<T> body) {
        Thread.currentThread().interrupt();
        T result;
        try {
            result = body.get();
        } catch (RuntimeException | Error thrown) {
            Thread.interrupted();
            throw thrown;
        }
        return new Interrupted<>(result, Thread.interrupted());
    }

    /**
     * {@code call} run on an interrupted thread, where it must abort, then whether the thread is still interrupted,
     * cleared afterwards.
     */
    private static Interrupted<TransitionAbortedException> abortedOnInterruptedThread(Executable call) {
        Thread.currentThread().interrupt();
        TransitionAbortedException thrown;
        boolean interrupted;
        try {
            thrown = assertThrows(TransitionAbortedException.class, call);
        } finally {
            interrupted = Thread.interrupted();
        }
        return new Interrupted<>(thrown, interrupted);
    }

    /** What a body answered on an interrupted thread, and whether the thread was still interrupted then. */
    private record Interrupted<T>(T result, boolean interrupted) { }

    /** A System.Logger whose level check, or whose write, blocks. */
    static final class BlockingLogger implements System.Logger {
        private final boolean checking;

        BlockingLogger(boolean checking) {
            this.checking = checking;
        }

        @Override
        public String getName() {
            return "blocking";
        }

        @Override
        public boolean isLoggable(System.Logger.Level level) {
            if (checking) {
                block();
            }
            return true;
        }

        @Override
        public void log(System.Logger.Level level, ResourceBundle bundle, String message, Throwable thrown) {
            block();
        }

        @Override
        public void log(System.Logger.Level level, ResourceBundle bundle, String format, Object... params) {
            block();
        }
    }

    /** A java.util.logging handler that blocks on the records that contain its target. */
    static final class BlockingHandler extends Handler {
        private final String target;

        BlockingHandler(String target) {
            this.target = target;
        }

        @Override
        public void publish(LogRecord record) {
            if (String.valueOf(record.getMessage()).contains(target)) {
                block();
            }
        }

        @Override
        public void flush() { }

        @Override
        public void close() { }
    }

    /** A value whose {@code toString()} blocks. */
    private static final Object BLOCKING_TEXT = new Object() {
        @Override
        public String toString() {
            block();
            return "never";
        }
    };

    /** A failure whose message read blocks. */
    static final class BlockingMessage extends RuntimeException {
        @Override
        public String getMessage() {
            block();
            return "never";
        }
    }

    @Test
    void aLevelCheckThatIsInterruptedCountsAsOffAndKeepsTheInterrupt() {
        Interrupted<CallLog> opened =
                onInterruptedThread(() -> CallLog.open(new BlockingLogger(true), Optional.empty()));
        assertSame(CallLog.OFF, opened.result());
        assertTrue(opened.interrupted(), "the thread lost its interrupt");
    }

    @Test
    void aWriteThatIsInterruptedLosesItsLineAndKeepsTheInterrupt() {
        CallLog log = CallLog.open(new BlockingLogger(false), Optional.empty());
        Interrupted<Boolean> written = onInterruptedThread(() -> {
            log.debug("start Doc/DocStatus SUBMIT");
            return log.debug();
        });
        assertEquals(true, written.result());
        assertTrue(written.interrupted(), "the thread lost its interrupt");
    }

    @Test
    void aValueWhoseToStringIsInterruptedPrintsAMarkerAndKeepsTheInterrupt() {
        Interrupted<String> shown = onInterruptedThread(() -> Texts.shown(BLOCKING_TEXT));
        assertEquals("<toString threw java.lang.InterruptedException>", shown.result());
        assertTrue(shown.interrupted(), "the thread lost its interrupt");
    }

    @Test
    void anIdentityWhoseToStringIsInterruptedPrintsAMarkerAndKeepsTheInterrupt() {
        Interrupted<String> shown = onInterruptedThread(() -> Texts.identity(BLOCKING_TEXT));
        assertEquals("<toString threw java.lang.InterruptedException>", shown.result());
        assertTrue(shown.interrupted(), "the thread lost its interrupt");
    }

    @Test
    void aVisibleFlowValueWhoseToStringIsInterruptedKeepsTheInterruptOfTheCall() {
        Doc doc = new Doc("1", DRAFT);
        FlowKey<Object> note = FlowKey.visible("note", Object.class);
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Submit",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.flow().put(note, BLOCKING_TEXT);
                            return ActionOutcome.ok();
                        }));
        try (LibraryLog log = LibraryLog.runtime(Level.FINER)) {
            Interrupted<Outcome<Doc, DocStatus, DocTransition>> called = onInterruptedThread(
                    () -> StateMachines.from(graph).state(Doc::status, Doc::setStatus).build().execute(doc, SUBMIT));
            assertEquals(OutcomeCode.APPLIED, called.result().code());
            assertTrue(called.interrupted(), "the thread lost its interrupt");
            assertTrue(log.records().stream().map(LogRecord::getMessage).anyMatch((String line) -> line.endsWith(
                    "flow-put note:Object=<toString threw java.lang.InterruptedException>")));
        }
    }

    @Test
    void anObserverThatIsInterruptedKeepsTheInterruptOfTheCall() {
        Doc doc = new Doc("1", DRAFT);
        MachineObserver blocking = new MachineObserver() {
            @Override
            public void onStarted(MachineCall call) {
                block();
            }
        };
        try (LibraryLog log = LibraryLog.capture();
             MachineRegistry registry = MachineRegistry.builder().observer(blocking).add(StateMachines.from(
                     docs(UnaryOperator.identity())).state(Doc::status, Doc::setStatus).identity(Doc::id)).build()) {
            Interrupted<Outcome<Doc, DocStatus, DocTransition>> called =
                    onInterruptedThread(() -> registry.of(DOCS).execute(doc, SUBMIT));
            assertEquals(OutcomeCode.APPLIED, called.result().code());
            assertTrue(called.interrupted(), "the thread lost its interrupt");
            assertEquals(1, log.records().size());
        }
    }

    @Test
    void anObserversWarningBackendThatIsInterruptedKeepsTheInterruptOfTheCall() {
        Doc doc = new Doc("1", DRAFT);
        MachineObserver failing = new MachineObserver() {
            @Override
            public void onStarted(MachineCall call) {
                throw new IllegalStateException("metrics down");
            }
        };
        BlockingHandler handler = new BlockingHandler("threw in");
        Logger logger = Logger.getLogger("io.github.graphmaquina");
        try (LibraryLog log = LibraryLog.capture();
             MachineRegistry registry = MachineRegistry.builder().observer(failing).add(StateMachines.from(
                     docs(UnaryOperator.identity())).state(Doc::status, Doc::setStatus).identity(Doc::id)).build()) {
            logger.addHandler(handler);
            try {
                Interrupted<Outcome<Doc, DocStatus, DocTransition>> called =
                        onInterruptedThread(() -> registry.of(DOCS).execute(doc, SUBMIT));
                assertEquals(OutcomeCode.APPLIED, called.result().code());
                assertTrue(called.interrupted(), "the thread lost its interrupt");
            } finally {
                logger.removeHandler(handler);
            }
            assertEquals(1, log.records().size());
        }
    }

    @Test
    void aTrailClockThatIsInterruptedOnTheFailedEntryKeepsTheInterruptOfTheCall() {
        Doc doc = new Doc("1", DRAFT);
        int[] reads = {0};
        InstantSource clock = () -> {
            if (++reads[0] == 2) {
                block();
            }
            return Instant.EPOCH;
        };
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Submit",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            throw new IllegalArgumentException("the action failed");
                        }));
        StateMachine<Doc, DocStatus, DocTransition> machine = StateMachines.from(graph)
                .state(Doc::status, Doc::setStatus).recordTrail(clock).build();
        Thread.currentThread().interrupt();
        TransitionAbortedException thrown;
        boolean interrupted;
        try {
            thrown = assertThrows(TransitionAbortedException.class, () -> machine.execute(doc, SUBMIT));
        } finally {
            interrupted = Thread.interrupted();
        }
        assertInstanceOf(InterruptedException.class, thrown.getSuppressed()[0]);
        assertTrue(interrupted, "the thread lost its interrupt");
        assertEquals(DRAFT, doc.status());
    }

    @Test
    void aRoutedFailureWhoseMessageReadIsInterruptedTakesItsRouteAndKeepsTheInterrupt() {
        Doc doc = new Doc("1", DRAFT);
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Submit",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            throw new BlockingMessage();
                        }).failsOn(BlockingMessage.class).onFailure(DISCARD));
        Interrupted<Outcome<Doc, DocStatus, DocTransition>> called = onInterruptedThread(
                () -> StateMachines.from(graph).state(Doc::status, Doc::setStatus).build().execute(doc, SUBMIT));
        Outcome.Failed<Doc, DocStatus, DocTransition> failed =
                (Outcome.Failed<Doc, DocStatus, DocTransition>) called.result();
        assertEquals("BlockingMessage: <getMessage threw java.lang.InterruptedException>", failed.reason());
        assertTrue(called.interrupted(), "the thread lost its interrupt");
        assertEquals(ARCHIVED, doc.status());
    }

    @Test
    void anActionThatIsInterruptedTakesTheRouteOfItsFailsOnAndKeepsTheInterrupt() {
        Doc doc = new Doc("1", DRAFT);
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Submit",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            block();
                            return ActionOutcome.ok();
                        }).failsOn(InterruptedException.class).onFailure(DISCARD));
        Interrupted<Outcome<Doc, DocStatus, DocTransition>> called = onInterruptedThread(
                () -> StateMachines.from(graph).state(Doc::status, Doc::setStatus).build().execute(doc, SUBMIT));
        Outcome.Failed<Doc, DocStatus, DocTransition> failed =
                (Outcome.Failed<Doc, DocStatus, DocTransition>) called.result();
        assertInstanceOf(InterruptedException.class, failed.cause().orElseThrow());
        assertTrue(called.interrupted(), "the thread lost its interrupt");
        assertEquals(ARCHIVED, doc.status());
    }

    /**
     * A routed interrupt, or one contained on the way to the route, leaves the rest of the call on an interrupted
     * thread, as an interrupt asks: the recovery's wait for a stage that is still pending, which would complete a
     * moment later, sees the interrupt at once and aborts the call, which holds the exception that selected the route
     * as suppressed.
     */
    @Test
    void aRecoveryThatWaitsForAPendingStageAfterAnInterruptAbortsAsInterruptedAndKeepsTheInterrupt() {
        Map<Class<? extends Throwable>, UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>>> routes =
                new LinkedHashMap<>();
        routes.put(InterruptedException.class, (TransitionBuilder<Doc, DocStatus, DocTransition> submit) ->
                submit.action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                    block();
                    return ActionOutcome.ok();
                }).failsOn(InterruptedException.class).onFailure(DISCARD));
        routes.put(BlockingMessage.class, (TransitionBuilder<Doc, DocStatus, DocTransition> submit) ->
                submit.action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                    throw new BlockingMessage();
                }).failsOn(BlockingMessage.class).onFailure(DISCARD));
        for (Map.Entry<Class<? extends Throwable>, UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>>>
                route : routes.entrySet()) {
            Doc doc = new Doc("1", DRAFT);
            CompletableFuture<ActionOutcome> pending = CompletableFuture.supplyAsync(ActionOutcome::ok,
                    CompletableFuture.delayedExecutor(200, TimeUnit.MILLISECONDS));
            StateMachine<Doc, DocStatus, DocTransition> machine = StateMachines.from(docs(route.getValue(),
                    (TransitionBuilder<Doc, DocStatus, DocTransition> discard) -> discard.asyncAction("Cleanup",
                            (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> pending)))
                    .state(Doc::status, Doc::setStatus).asyncTimeout(Duration.ofSeconds(5)).build();
            Interrupted<TransitionAbortedException> called =
                    abortedOnInterruptedThread(() -> machine.execute(doc, SUBMIT));
            assertTrue(called.result().getMessage().contains("interrupted while waiting for the action Cleanup"),
                    called.result().getMessage());
            assertInstanceOf(InterruptedException.class, called.result().getCause());
            assertInstanceOf(route.getKey(), called.result().getSuppressed()[0]);
            assertTrue(called.interrupted(), "the thread lost its interrupt");
            assertEquals(DRAFT, doc.status());
        }
    }

    /**
     * A recovery that need not wait, a sync action or a stage already completed, completes on the interrupted thread.
     */
    @Test
    void aRecoveryThatNeedNotWaitAfterARoutedInterruptCompletesAndKeepsTheInterrupt() {
        List<UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>>> recoveries = List.of(
                (TransitionBuilder<Doc, DocStatus, DocTransition> discard) -> discard.action("Cleanup",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> ActionOutcome.ok()),
                (TransitionBuilder<Doc, DocStatus, DocTransition> discard) -> discard.asyncAction("Cleanup",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) ->
                                CompletableFuture.completedFuture(ActionOutcome.ok())));
        for (UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>> recovery : recoveries) {
            Doc doc = new Doc("1", DRAFT);
            StateMachine<Doc, DocStatus, DocTransition> machine = StateMachines.from(docs(
                    (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Submit",
                            (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                                block();
                                return ActionOutcome.ok();
                            }).failsOn(InterruptedException.class).onFailure(DISCARD), recovery))
                    .state(Doc::status, Doc::setStatus).asyncTimeout(Duration.ofSeconds(5)).build();
            Interrupted<Outcome<Doc, DocStatus, DocTransition>> called =
                    onInterruptedThread(() -> machine.execute(doc, SUBMIT));
            Outcome.Failed<Doc, DocStatus, DocTransition> failed =
                    (Outcome.Failed<Doc, DocStatus, DocTransition>) called.result();
            assertInstanceOf(InterruptedException.class, failed.cause().orElseThrow());
            assertTrue(called.interrupted(), "the thread lost its interrupt");
            assertEquals(ARCHIVED, doc.status());
        }
    }

    @Test
    void anActionThatIsInterruptedWithNoRouteAbortsTheCallAndKeepsTheInterrupt() {
        Doc doc = new Doc("1", DRAFT);
        StateMachine<Doc, DocStatus, DocTransition> machine = StateMachines.from(docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Submit",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            block();
                            return ActionOutcome.ok();
                        }))).state(Doc::status, Doc::setStatus).build();
        Interrupted<TransitionAbortedException> called = abortedOnInterruptedThread(() -> machine.execute(doc, SUBMIT));
        assertInstanceOf(InterruptedException.class, called.result().getCause());
        assertTrue(called.interrupted(), "the thread lost its interrupt");
        assertEquals(DRAFT, doc.status());
    }

    @Test
    void anAsyncActionThatIsInterruptedBeforeItReturnsItsStageAbortsTheCallAndKeepsTheInterrupt() {
        Doc doc = new Doc("1", DRAFT);
        StateMachine<Doc, DocStatus, DocTransition> machine = StateMachines.from(docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.asyncAction("Submit",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            block();
                            return CompletableFuture.completedFuture(ActionOutcome.ok());
                        }))).state(Doc::status, Doc::setStatus).asyncTimeout(Duration.ofSeconds(5)).build();
        Interrupted<TransitionAbortedException> called = abortedOnInterruptedThread(() -> machine.execute(doc, SUBMIT));
        assertInstanceOf(InterruptedException.class, called.result().getCause());
        assertTrue(called.interrupted(), "the thread lost its interrupt");
        assertEquals(DRAFT, doc.status());
    }

    @Test
    void aGuardThatIsInterruptedAbortsTheCallAndKeepsTheInterrupt() {
        Doc doc = new Doc("1", DRAFT);
        StateMachine<Doc, DocStatus, DocTransition> machine = StateMachines.from(docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.guard("Ready",
                        (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                            block();
                            return GuardResult.pass();
                        }))).state(Doc::status, Doc::setStatus).build();
        Interrupted<TransitionAbortedException> called = abortedOnInterruptedThread(() -> machine.execute(doc, SUBMIT));
        assertInstanceOf(InterruptedException.class, called.result().getCause());
        assertTrue(called.interrupted(), "the thread lost its interrupt");
        assertEquals(DRAFT, doc.status());
    }

    @Test
    void anAsyncGuardThatIsInterruptedBeforeItReturnsItsStageAbortsTheCallAndKeepsTheInterrupt() {
        Doc doc = new Doc("1", DRAFT);
        StateMachine<Doc, DocStatus, DocTransition> machine = StateMachines.from(docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.asyncGuard("Ready",
                        (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                            block();
                            return CompletableFuture.completedFuture(GuardResult.pass());
                        }))).state(Doc::status, Doc::setStatus).asyncTimeout(Duration.ofSeconds(5)).build();
        Interrupted<TransitionAbortedException> called = abortedOnInterruptedThread(() -> machine.execute(doc, SUBMIT));
        assertInstanceOf(InterruptedException.class, called.result().getCause());
        assertTrue(called.interrupted(), "the thread lost its interrupt");
        assertEquals(DRAFT, doc.status());
    }

    /** An interrupt that completes a stage is no interrupt of the calling thread, which the engine leaves as it is. */
    @Test
    void aStageThatCompletesWithAnInterruptLeavesTheCallingThreadUninterrupted() {
        Doc doc = new Doc("1", DRAFT);
        StateMachine<Doc, DocStatus, DocTransition> machine = StateMachines.from(docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.asyncAction("Submit",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> CompletableFuture.failedFuture(
                                new InterruptedException("interrupted elsewhere")))))
                .state(Doc::status, Doc::setStatus).asyncTimeout(Duration.ofSeconds(5)).build();
        TransitionAbortedException thrown;
        boolean interrupted;
        try {
            thrown = assertThrows(TransitionAbortedException.class, () -> machine.execute(doc, SUBMIT));
        } finally {
            interrupted = Thread.interrupted();
        }
        assertInstanceOf(InterruptedException.class, thrown.getCause());
        assertEquals(false, interrupted);
        assertEquals(DRAFT, doc.status());
    }

    @Test
    void anOwnerLookupThatIsInterruptedAbortsItsReactionAndKeepsTheInterruptOfTheCall() {
        Doc doc = new Doc("1", DRAFT);
        GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS).initial(DRAFT)
                .terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted").statePolicy(REVIEW, "a review is published")
                .statePolicy(PUBLISHED, "a publication is archived")
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .watch(PUBLISHED, DOCS, (Doc owner) -> List.of(owner), (Doc moved) -> {
                    block();
                    return Optional.<Doc>empty();
                })
                    .when("always", (Doc owner, Collection<? extends Doc> peers,
                            PeerView<Doc, DocStatus, DocTransition> view) -> true).execute(ARCHIVE)
                .endWatch()
                .build();
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(graph).state(Doc::status, Doc::setStatus).identity(Doc::id)).build()) {
            Interrupted<Outcome<Doc, DocStatus, DocTransition>> called =
                    onInterruptedThread(() -> registry.of(DOCS).execute(doc, SUBMIT));
            Reaction.Aborted aborted = assertInstanceOf(Reaction.Aborted.class, called.result().reactions().get(0));
            UndeclaredThrowableException wrapper =
                    assertInstanceOf(UndeclaredThrowableException.class, aborted.cause());
            assertInstanceOf(InterruptedException.class, wrapper.getCause());
            assertTrue(called.interrupted(), "the thread lost its interrupt");
            assertEquals(REVIEW, doc.status());
        }
    }

    /**
     * How a call ended: the code of its outcome, or the class of the failure it threw, with the failure's phase and
     * the class of its cause.
     */
    private static String ended(Supplier<? extends Outcome<?, ?, ?>> call) {
        try {
            return call.get().code().name();
        } catch (CallFailedException failure) {
            return failure.getClass().getSimpleName() + " in " + failure.phase() + " caused by "
                    + (failure.getCause() == null ? "nothing" : failure.getCause().getClass().getSimpleName());
        }
    }

    /** A standalone machine over the doc graph, with {@code getter} and {@code setter} as its state accessors. */
    private static StateMachines.Builder<Doc, DocStatus, DocTransition> docMachine(
            GraphDefinition<Doc, DocStatus, DocTransition> graph, Function<Doc, DocStatus> getter,
            BiConsumer<Doc, DocStatus> setter) {
        return StateMachines.from(graph).state(getter, setter);
    }

    /** A getter of the doc's status that blocks on its {@code blocking}th read, counting from one. */
    private static Function<Doc, DocStatus> statusBlockingOnRead(int blocking) {
        int[] reads = {0};
        return (Doc doc) -> {
            if (++reads[0] == blocking) {
                block();
            }
            return doc.status();
        };
    }

    private static String rootIdentity() {
        return ended(() -> docMachine(docs(UnaryOperator.identity()), Doc::status, Doc::setStatus)
                .identity((Doc doc) -> {
                    block();
                    return doc.id();
                }).build().execute(new Doc("1", DRAFT), SUBMIT));
    }

    private static String rootGetter() {
        return ended(() -> docMachine(docs(UnaryOperator.identity()), statusBlockingOnRead(1), Doc::setStatus).build()
                .execute(new Doc("1", DRAFT), SUBMIT));
    }

    /** The first read is the step's; the second is the commit's check. */
    private static String commitCheckGetter() {
        return ended(() -> docMachine(docs(UnaryOperator.identity()), statusBlockingOnRead(2), Doc::setStatus).build()
                .execute(new Doc("1", DRAFT), SUBMIT));
    }

    private static String setter() {
        return ended(() -> docMachine(docs(UnaryOperator.identity()), Doc::status, (Doc doc, DocStatus status) -> {
            block();
            doc.setStatus(status);
        }).build().execute(new Doc("1", DRAFT), SUBMIT));
    }

    private static String hook() {
        return ended(() -> docMachine(docs((TransitionBuilder<Doc, DocStatus, DocTransition> submit) ->
                submit.afterCommit("Notify", (Doc doc) -> block())), Doc::status, Doc::setStatus).build()
                .execute(new Doc("1", DRAFT), SUBMIT));
    }

    /** The first read marks the hop of SUBMIT; the second dates the Failed entry. */
    private static String trailClock() {
        int[] reads = {0};
        InstantSource clock = () -> {
            if (++reads[0] == 1) {
                block();
            }
            return Instant.EPOCH;
        };
        return ended(() -> docMachine(docs(UnaryOperator.identity()), Doc::status, Doc::setStatus).recordTrail(clock)
                .build().execute(new Doc("1", DRAFT), SUBMIT));
    }

    /** SUBMIT's action cascades PUBLISH into a peer whose state read blocks, and lets the failure through. */
    private static String cascadedPeerGetter() {
        Doc peer = new Doc("2", REVIEW);
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Submit",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.cascade(DOCS, peer, PUBLISH);
                            return ActionOutcome.ok();
                        }));
        return ended(() -> docMachine(graph, (Doc doc) -> {
            if (doc == peer) {
                block();
            }
            return doc.status();
        }, Doc::setStatus).build().execute(new Doc("1", DRAFT), SUBMIT));
    }

    private static String reevaluatedGetter() {
        return ended(() -> docMachine(docs(UnaryOperator.identity()), statusBlockingOnRead(1), Doc::setStatus).build()
                .reevaluate(new Doc("1", DRAFT)));
    }

    private static String watchCondition() {
        GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS).initial(DRAFT)
                .terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted").statePolicy(REVIEW, "a review is published")
                .statePolicy(PUBLISHED, "a publication is archived")
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .watch(DRAFT, DOCS, (Doc owner) -> List.of(owner), (Doc moved) -> Optional.of(moved))
                    .when("blocks", (Doc owner, Collection<? extends Doc> peers,
                            PeerView<Doc, DocStatus, DocTransition> view) -> {
                        block();
                        return true;
                    }).execute(SUBMIT)
                .endWatch()
                .build();
        return ended(() -> docMachine(graph, Doc::status, Doc::setStatus).build().reevaluate(new Doc("1", DRAFT)));
    }

    /** HOLD of a deposit, whose binding check runs the lookups of the booking that holds it in DEPOSIT. */
    private static String deposited(MachineRegistry registry) {
        Payment deposit = new Payment("d1", PaymentStatus.AUTHORIZING);
        new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(deposit);
        try (registry) {
            return ended(() -> registry.of(PAYMENTS).execute(deposit, PaymentTransition.HOLD));
        }
    }

    private static String parentOf() {
        Billing billing = new Billing();
        billing.bookingOf = (Payment payment) -> {
            block();
            return payment.booking();
        };
        return deposited(billing.registry());
    }

    private static String childOfInTheBindingCheck() {
        Billing billing = new Billing();
        billing.depositOf = (Booking booking) -> {
            block();
            return booking.deposit();
        };
        return deposited(billing.registry());
    }

    private static String parentGetterInTheBindingCheck() {
        Billing billing = new Billing();
        return deposited(MachineRegistry.builder()
                .add(StateMachines.from(billing.bookings).state((Booking booking) -> {
                    block();
                    return booking.status();
                }, Booking::setStatus).identity(Booking::id))
                .add(billing.paymentMachine())
                .build());
    }

    /** BOOK of a booking, which enters DEPOSIT and starts the deposit it holds there. */
    private static String booked(MachineRegistry registry) {
        Booking booking = new Booking("b1", BookingStatus.REQUESTED).attachDeposit(
                new Payment("d1", PaymentStatus.AUTHORIZING));
        try (registry) {
            return ended(() -> registry.of(BOOKINGS).execute(booking, BookingTransition.BOOK));
        }
    }

    private static String childOfAtTheEntry() {
        Billing billing = new Billing();
        billing.depositOf = (Booking booking) -> {
            block();
            return booking.deposit();
        };
        return booked(billing.registry());
    }

    private static String childGetterAtTheEntry() {
        Billing billing = new Billing();
        return booked(MachineRegistry.builder()
                .add(billing.bookingMachine())
                .add(StateMachines.from(billing.payments).state((Payment payment) -> {
                    block();
                    return payment.status();
                }, Payment::setStatus).identity(Payment::id))
                .build());
    }

    /** A supplier that a machine whose executor runs every task inline submits through supplyAsync. */
    private static String submittedSupplier() {
        StateMachine<Doc, DocStatus, DocTransition> machine = docMachine(docs(UnaryOperator.identity()), Doc::status,
                Doc::setStatus).asyncExecutor((Runnable task) -> task.run()).build();
        CompletableFuture<Object> future = machine.supplyAsync(() -> {
            block();
            return "never";
        });
        Throwable failure = future.handle((Object value, Throwable error) -> error).join();
        return failure.getClass().getSimpleName() + " caused by " + failure.getCause().getClass().getSimpleName();
    }

    /**
     * Each place where the engine contains what application code threw on the calling thread, here an
     * InterruptedException that a blocking call threw once it cleared the interrupt: the call ends as it does for any
     * other throwable there, and the thread is interrupted again.
     */
    @Test
    void everyCallbackThatIsInterruptedOnTheCallingThreadKeepsTheInterrupt() {
        Map<String, Supplier<String>> calls = new LinkedHashMap<>();
        calls.put("the root's identity function", InterruptedContainmentTest::rootIdentity);
        calls.put("the root's state getter", InterruptedContainmentTest::rootGetter);
        calls.put("the getter in the commit's check", InterruptedContainmentTest::commitCheckGetter);
        calls.put("a state setter", InterruptedContainmentTest::setter);
        calls.put("an after-commit hook", InterruptedContainmentTest::hook);
        calls.put("the trail's clock", InterruptedContainmentTest::trailClock);
        calls.put("a cascaded peer's state getter", InterruptedContainmentTest::cascadedPeerGetter);
        calls.put("a reevaluated entity's state getter", InterruptedContainmentTest::reevaluatedGetter);
        calls.put("a watch condition", InterruptedContainmentTest::watchCondition);
        calls.put("a parentOf", InterruptedContainmentTest::parentOf);
        calls.put("a childOf in the binding check", InterruptedContainmentTest::childOfInTheBindingCheck);
        calls.put("a parent's state getter in the binding check",
                InterruptedContainmentTest::parentGetterInTheBindingCheck);
        calls.put("a childOf at a sub-machine's entry", InterruptedContainmentTest::childOfAtTheEntry);
        calls.put("a child's state getter at a sub-machine's entry", InterruptedContainmentTest::childGetterAtTheEntry);
        calls.put("a supplier submitted through supplyAsync", InterruptedContainmentTest::submittedSupplier);
        List<String> ends = new ArrayList<>();
        for (Map.Entry<String, Supplier<String>> call : calls.entrySet()) {
            Thread.currentThread().interrupt();
            String end;
            boolean interrupted;
            try {
                end = call.getValue().get();
            } finally {
                interrupted = Thread.interrupted();
            }
            ends.add(call.getKey() + ": " + end + ", interrupted " + interrupted);
        }
        String aborted = "TransitionAbortedException in ";
        String interrupt = " caused by InterruptedException, interrupted true";
        assertEquals(List.of(
                "the root's identity function: " + aborted + "RESOLVE" + interrupt,
                "the root's state getter: " + aborted + "RESOLVE" + interrupt,
                "the getter in the commit's check: CommitFailedException in COMMIT" + interrupt,
                "a state setter: CommitFailedException in COMMIT" + interrupt,
                "an after-commit hook: AfterCommitException in HOOK" + interrupt,
                "the trail's clock: " + aborted + "ACTION" + interrupt,
                "a cascaded peer's state getter: " + aborted + "ACTION" + interrupt,
                "a reevaluated entity's state getter: " + aborted + "RESOLVE" + interrupt,
                "a watch condition: " + aborted + "RESOLVE" + interrupt,
                "a parentOf: " + aborted + "RESOLVE" + interrupt,
                "a childOf in the binding check: " + aborted + "RESOLVE" + interrupt,
                "a parent's state getter in the binding check: " + aborted + "RESOLVE" + interrupt,
                "a childOf at a sub-machine's entry: " + aborted + "ACTION" + interrupt,
                "a child's state getter at a sub-machine's entry: " + aborted + "ACTION" + interrupt,
                "a supplier submitted through supplyAsync: CompletionException" + interrupt), ends);
    }

    @Test
    void aThreadThatWasNeverInterruptedStaysUninterrupted() {
        assertEquals(List.of("<toString threw java.lang.IllegalStateException>"), List.of(Texts.shown(new Object() {
            @Override
            public String toString() {
                throw new IllegalStateException("unprintable");
            }
        })));
        assertEquals(false, Thread.currentThread().isInterrupted());
    }
}
