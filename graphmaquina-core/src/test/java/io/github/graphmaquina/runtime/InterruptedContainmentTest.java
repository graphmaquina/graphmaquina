package io.github.graphmaquina.runtime;

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
import io.github.graphmaquina.api.MachineCall;
import io.github.graphmaquina.api.MachineObserver;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.OutcomeCode;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.GraphBuilder;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import io.github.graphmaquina.fixtures.LibraryLog;
import io.github.graphmaquina.fixtures.Sneaky;
import io.github.graphmaquina.internal.Texts;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

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

    /** The doc graph, with {@code submit} decorating its submit edge. */
    private static GraphDefinition<Doc, DocStatus, DocTransition> docs(
            UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>> submit) {
        GraphBuilder<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted or discarded").statePolicy(REVIEW, "a review is published")
                .statePolicy(PUBLISHED, "a publication is archived");
        graph = submit.apply(graph.transition(SUBMIT, DRAFT, REVIEW)).endTransition();
        return graph.transition(DISCARD, DRAFT, ARCHIVED).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
    }

    @Test
    void aLevelCheckThatIsInterruptedCountsAsOffAndKeepsTheInterrupt() {
        Interrupted<CallLog> opened = onInterruptedThread(() -> CallLog.open(new BlockingLogger(true), Optional.empty()));
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
