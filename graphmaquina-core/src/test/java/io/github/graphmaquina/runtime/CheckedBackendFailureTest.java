package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.DocGraphs.docs;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.DISCARD;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.CallFailure;
import io.github.graphmaquina.api.FlowEntry;
import io.github.graphmaquina.api.FlowKey;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineCall;
import io.github.graphmaquina.api.MachineObserver;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.OutcomeCode;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import io.github.graphmaquina.fixtures.LibraryLog;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.function.UnaryOperator;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * A log backend, the backend of the observers' warning or a trail clock that throws a checked exception it never
 * declared, as Kotlin code or a sneaky throw can, is held to the rule of one that throws a RuntimeException: the log
 * loses its line or counts its level as off and the warning is lost, neither of which changes how the call ends, and a
 * clock failure fails the call, as the cause of its abort, or, on the instant of the Failed entry, is suppressed.
 */
class CheckedBackendFailureTest {

    private static final FlowKey<String> NOTE = FlowKey.of("note", String.class);

    @SuppressWarnings("unchecked")
    private static <X extends Throwable> void sneaky(Throwable thrown) throws X {
        throw (X) thrown;
    }

    private static void throwUndeclared(Throwable checked) {
        CheckedBackendFailureTest.<RuntimeException>sneaky(checked);
    }

    /** A java.util.logging handler that throws an undeclared IOException on the records that contain its target. */
    static final class FailingHandler extends Handler {
        private final String target;

        FailingHandler(String target) {
            this.target = target;
        }

        @Override
        public void publish(LogRecord record) {
            if (record.getMessage().contains(target)) {
                throwUndeclared(new IOException("disk full"));
            }
        }

        @Override
        public void flush() { }

        @Override
        public void close() { }
    }

    /** A System.Logger whose level check or whose write throws an undeclared IOException. */
    static final class FailingLogger implements System.Logger {
        private final boolean checking;

        FailingLogger(boolean checking) {
            this.checking = checking;
        }

        @Override
        public String getName() {
            return "failing";
        }

        @Override
        public boolean isLoggable(Level level) {
            if (checking) {
                throwUndeclared(new IOException("disk full"));
            }
            return true;
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String message, Throwable thrown) {
            throwUndeclared(new IOException("disk full"));
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String format, Object... params) {
            throwUndeclared(new IOException("disk full"));
        }
    }

    /** Records how each call ends for its observers. */
    private static MachineObserver recording(List<String> events) {
        return new MachineObserver() {
            @Override
            public void onStarted(MachineCall call) {
                events.add("started");
            }

            @Override
            public void onCompleted(MachineCall call, Outcome<?, ?, ?> outcome, Duration elapsed) {
                events.add("completed " + outcome.code());
            }

            @Override
            public void onFailed(MachineCall call, CallFailure failure, Duration elapsed) {
                events.add("failed " + failure.phase());
            }
        };
    }

    @Test
    void aFlowLineWhoseBackendThrowsACheckedExceptionNeitherAbortsNorRoutesTheStep() {
        Doc doc = new Doc("1", DRAFT);
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Submit",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.flow().put(NOTE, "written");
                            return ActionOutcome.ok();
                        }).failsOn(IOException.class).onFailure(DISCARD));
        FailingHandler handler = new FailingHandler("flow-put");
        Logger logger = Logger.getLogger(CallLog.LOGGER_NAME);
        try (LibraryLog log = LibraryLog.runtime(Level.FINER)) {
            logger.addHandler(handler);
            try {
                Outcome<Doc, DocStatus, DocTransition> outcome = StateMachines.from(graph)
                        .state(Doc::status, Doc::setStatus).build().execute(doc, SUBMIT);
                assertEquals(OutcomeCode.APPLIED, outcome.code());
                assertEquals(Optional.of("written"), outcome.flow().get(NOTE));
                assertEquals(false, log.records().isEmpty());
            } finally {
                logger.removeHandler(handler);
            }
        }
        assertEquals(REVIEW, doc.status());
    }

    @Test
    void aCommittedLineWhoseBackendThrowsACheckedExceptionLeavesTheCallApplied() {
        Doc doc = new Doc("1", DRAFT);
        List<String> events = new ArrayList<>();
        FailingHandler handler = new FailingHandler("committed");
        Logger logger = Logger.getLogger(CallLog.LOGGER_NAME);
        try (LibraryLog log = LibraryLog.runtime(Level.FINE)) {
            logger.addHandler(handler);
            try (MachineRegistry registry = MachineRegistry.builder().observer(recording(events))
                    .add(StateMachines.from(docs(UnaryOperator.identity())).state(Doc::status, Doc::setStatus)
                            .identity(Doc::id)).build()) {
                assertEquals(OutcomeCode.APPLIED, registry.of(DOCS).execute(doc, SUBMIT).code());
                assertEquals(false, log.records().isEmpty());
            } finally {
                logger.removeHandler(handler);
            }
        }
        assertEquals(List.of("started", "completed APPLIED"), events);
        assertEquals(REVIEW, doc.status());
    }

    @Test
    void aLevelCheckThatThrowsACheckedExceptionCountsAsOff() {
        assertSame(CallLog.OFF, CallLog.open(new FailingLogger(true), Optional.empty()));
    }

    @Test
    void aWriteThatThrowsACheckedExceptionLosesItsLine() {
        CallLog log = CallLog.open(new FailingLogger(false), Optional.empty());
        log.debug("start Doc/DocStatus SUBMIT");
        log.trace(0, "resolved Doc/DocStatus#1 DRAFT -SUBMIT-> REVIEW kind=MOVE");
        assertEquals(true, log.debug());
    }

    @Test
    void aValueWhoseToStringThrowsACheckedExceptionPrintsAMarker() {
        Object unprintable = new Object() {
            @Override
            public String toString() {
                throwUndeclared(new IOException("unprintable"));
                return "never";
            }
        };
        assertEquals("<toString threw java.io.IOException>", CallLog.show(unprintable));
    }

    @Test
    void aWarningBackendThatThrowsACheckedExceptionLetsTheCallRunForEveryObserver() {
        Doc doc = new Doc("1", DRAFT);
        List<String> events = new ArrayList<>();
        MachineObserver failing = new MachineObserver() {
            @Override
            public void onStarted(MachineCall call) {
                throw new IllegalStateException("metrics down");
            }
        };
        FailingHandler handler = new FailingHandler("threw in");
        Logger logger = Logger.getLogger("io.github.graphmaquina");
        try (LibraryLog log = LibraryLog.capture()) {
            logger.addHandler(handler);
            try (MachineRegistry registry = MachineRegistry.builder().observer(failing).observer(recording(events))
                    .add(StateMachines.from(docs(UnaryOperator.identity())).state(Doc::status, Doc::setStatus)
                            .identity(Doc::id)).build()) {
                assertEquals(OutcomeCode.APPLIED, registry.of(DOCS).execute(doc, SUBMIT).code());
                assertEquals(1, log.records().size());
            } finally {
                logger.removeHandler(handler);
            }
        }
        assertEquals(List.of("started", "completed APPLIED"), events);
        assertEquals(REVIEW, doc.status());
    }

    @Test
    void aTrailClockThatThrowsACheckedExceptionOnAHopFailsTheCall() {
        Doc doc = new Doc("1", DRAFT);
        List<String> events = new ArrayList<>();
        IOException offline = new IOException("clock offline");
        InstantSource clock = () -> {
            throwUndeclared(offline);
            return Instant.EPOCH;
        };
        try (MachineRegistry registry = MachineRegistry.builder().observer(recording(events))
                .add(StateMachines.from(docs(UnaryOperator.identity())).state(Doc::status, Doc::setStatus)
                        .identity(Doc::id).recordTrail(clock)).build()) {
            TransitionAbortedException thrown = assertThrows(TransitionAbortedException.class,
                    () -> registry.of(DOCS).execute(doc, SUBMIT));
            assertSame(offline, thrown.getCause());
        }
        assertEquals(List.of("started", "failed ACTION"), events);
        assertEquals(DRAFT, doc.status());
    }

    @Test
    void aTrailClockThatThrowsACheckedExceptionOnTheFailedEntryIsSuppressed() {
        Doc doc = new Doc("1", DRAFT);
        List<String> events = new ArrayList<>();
        IOException offline = new IOException("clock offline");
        int[] reads = {0};
        InstantSource clock = () -> {
            if (++reads[0] == 2) {
                throwUndeclared(offline);
            }
            return Instant.EPOCH;
        };
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Submit",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            throw new IllegalArgumentException("the action failed");
                        }));
        try (MachineRegistry registry = MachineRegistry.builder().observer(recording(events))
                .add(StateMachines.from(graph).state(Doc::status, Doc::setStatus).identity(Doc::id)
                        .recordTrail(clock)).build()) {
            TransitionAbortedException thrown = assertThrows(TransitionAbortedException.class,
                    () -> registry.of(DOCS).execute(doc, SUBMIT));
            assertEquals(List.of(offline), List.of(thrown.getSuppressed()));
            List<FlowEntry> trail = thrown.flow().trail();
            assertEquals(1, trail.size());
            assertInstanceOf(FlowEntry.Hop.class, trail.get(0));
        }
        assertEquals(List.of("started", "failed ACTION"), events);
        assertEquals(DRAFT, doc.status());
    }
}
