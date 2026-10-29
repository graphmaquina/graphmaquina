package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.DocGraphs.docs;
import static io.github.graphmaquina.fixtures.DocGraphs.plain;
import static io.github.graphmaquina.fixtures.DocMachines.registry;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static io.github.graphmaquina.fixtures.LogLines.lines;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.CallFailure;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.MachineCall;
import io.github.graphmaquina.api.MachineObserver;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.OutcomeCode;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import io.github.graphmaquina.fixtures.Fatal;
import io.github.graphmaquina.fixtures.LibraryLog;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * The DEBUG lines of the runtime log when the backend that writes them throws, read through
 * {@code java.util.logging} at {@code FINE} like those of {@link RuntimeLogDebugTest}. A backend that throws a
 * RuntimeException changes nothing about the call; one that throws an Error ends the call with that very Error,
 * whatever line it throws on, and the observers hear that Error as the end of the call, in the phase the call reached
 * and with what it wrote.
 */
class RuntimeLogBackendFailureTest {

    @Test
    void aBackendThatThrowsARuntimeExceptionChangesNothing() {
        Doc doc = new Doc("1", DRAFT);
        Handler failing = new Handler() {
            @Override
            public void publish(LogRecord record) {
                throw new IllegalStateException("disk full");
            }

            @Override
            public void flush() { }

            @Override
            public void close() { }
        };
        Logger runtime = Logger.getLogger(CallLog.LOGGER_NAME);
        try (MachineRegistry registry = registry(plain()); LibraryLog log = LibraryLog.runtime(Level.FINE)) {
            runtime.addHandler(failing);
            try {
                assertEquals(OutcomeCode.APPLIED, registry.of(DOCS).execute(doc, SUBMIT).code());
            } finally {
                runtime.removeHandler(failing);
            }
            assertEquals(3, log.records().size());
            assertEquals(REVIEW, doc.status());
        }
    }

    @Test
    void aBackendErrorPropagates() {
        Doc doc = new Doc("1", DRAFT);
        Fatal broken = new Fatal("handler invariant");
        Handler failing = new Handler() {
            @Override
            public void publish(LogRecord record) {
                throw broken;
            }

            @Override
            public void flush() { }

            @Override
            public void close() { }
        };
        Logger runtime = Logger.getLogger(CallLog.LOGGER_NAME);
        try (MachineRegistry registry = registry(plain()); LibraryLog log = LibraryLog.runtime(Level.FINE)) {
            runtime.addHandler(failing);
            try {
                assertSame(broken, assertThrows(Fatal.class, () -> registry.of(DOCS).execute(doc, SUBMIT)));
            } finally {
                runtime.removeHandler(failing);
            }
            assertEquals(List.of("FINE call=A start Doc/DocStatus SUBMIT"), lines(log));
            assertEquals(DRAFT, doc.status());
        }
    }

    /**
     * The start line is written inside the try that tells the observers of an Error: a backend that throws an Error
     * on that line ends the call, of execute as of reevaluate, and the observers, told the call started, are told it
     * failed with that very Error.
     */
    @Test
    void anErrorOfTheBackendOnTheStartLineReachesTheObservers() {
        Fatal broken = new Fatal("handler invariant");
        Handler failing = new Handler() {
            @Override
            public void publish(LogRecord record) {
                throw broken;
            }

            @Override
            public void flush() { }

            @Override
            public void close() { }
        };
        List<String> heard = new ArrayList<>();
        MachineObserver observer = new MachineObserver() {
            @Override
            public void onStarted(MachineCall call) {
                heard.add("started");
            }

            @Override
            public void onFailed(MachineCall call, CallFailure failure, Duration elapsed) {
                heard.add(failure.error() == broken ? "failed with the backend's Error" : "failed with another");
            }
        };
        Logger runtime = Logger.getLogger(CallLog.LOGGER_NAME);
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(plain()).state(Doc::status, Doc::setStatus).identity(Doc::id))
                .observer(observer)
                .build(); LibraryLog log = LibraryLog.runtime(Level.FINE)) {
            runtime.addHandler(failing);
            try {
                assertSame(broken, assertThrows(Fatal.class,
                        () -> registry.of(DOCS).execute(new Doc("1", DRAFT), SUBMIT)));
                assertSame(broken, assertThrows(Fatal.class, () -> registry.of(DOCS).reevaluate(new Doc("2", DRAFT))));
            } finally {
                runtime.removeHandler(failing);
            }
            assertEquals(List.of("FINE call=A start Doc/DocStatus SUBMIT",
                    "FINE call=B start Doc/DocStatus reevaluate"), lines(log));
        }
        assertEquals(List.of("started", "failed with the backend's Error", "started",
                "failed with the backend's Error"), heard);
    }

    /**
     * A backend Error on a line the call logs once it ran, its committed line or its end or failed line, ends the
     * call: the caller receives that very Error, and so do the observers, as their one end event, never the outcome or
     * the failure the call would have returned or thrown, with the phase the call reached and what it wrote. The
     * committed line comes once every setter returned, so an Error on it reports every state written.
     */
    @Test
    void anErrorOfTheBackendOnALaterLineReachesTheObservers() {
        GraphDefinition<Doc, DocStatus, DocTransition> rejecting = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.guard("Ready",
                        (GuardContext<Doc, DocStatus, DocTransition> ctx) -> GuardResult.reject("not ready")));
        GraphDefinition<Doc, DocStatus, DocTransition> throwing = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Review",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            throw new IllegalStateException("reviewer unavailable");
                        }));
        Doc committed = new Doc("1", DRAFT);
        assertEquals(List.of("failed with the backend's Error in HOOK, ALL"), terminalEvents(" committed ",
                readable(plain()), (StateMachine<Doc, DocStatus, DocTransition> machine) ->
                        machine.execute(committed, SUBMIT)));
        assertEquals(REVIEW, committed.status());
        Doc applied = new Doc("2", DRAFT);
        assertEquals(List.of("failed with the backend's Error in REACTIONS, ALL"), terminalEvents(" end code=",
                readable(plain()), (StateMachine<Doc, DocStatus, DocTransition> machine) ->
                        machine.execute(applied, SUBMIT)));
        assertEquals(REVIEW, applied.status());
        assertEquals(List.of("failed with the backend's Error in GUARD, NONE"), terminalEvents(" end code=",
                readable(rejecting), (StateMachine<Doc, DocStatus, DocTransition> machine) ->
                        machine.execute(new Doc("3", DRAFT), SUBMIT)));
        assertEquals(List.of("failed with the backend's Error in RESOLVE, NONE"), terminalEvents(" end code=",
                readable(plain()), (StateMachine<Doc, DocStatus, DocTransition> machine) ->
                        machine.reevaluate(new Doc("4", DRAFT))));
        assertEquals(List.of("failed with the backend's Error in ACTION, NONE"), terminalEvents(" failed phase=",
                readable(throwing), (StateMachine<Doc, DocStatus, DocTransition> machine) ->
                        machine.execute(new Doc("5", DRAFT), SUBMIT)));
        assertEquals(List.of("failed with the backend's Error in RESOLVE, NONE"), terminalEvents(" failed phase=",
                StateMachines.from(plain())
                        .state((Doc doc) -> {
                            throw new IllegalStateException("unreadable");
                        }, Doc::setStatus)
                        .identity(Doc::id),
                (StateMachine<Doc, DocStatus, DocTransition> machine) -> machine.reevaluate(new Doc("6", DRAFT))));
    }

    /**
     * The end line of a call that committed comes once its post-commit ran, in the last phase it entered: REACTIONS in
     * a registry whose graphs have no automatic edge, which runs no automatic chain, and AUTOMATIC_STEPS in one whose
     * graph has one, here SUBMIT, although the doc's chain from REVIEW makes no attempt.
     */
    @Test
    void theEndLineOfACommittedCallFailsInTheLastPhaseItEntered() {
        Doc plainDoc = new Doc("1", DRAFT);
        assertEquals(List.of("failed with the backend's Error in REACTIONS, ALL"), terminalEvents(" end code=",
                readable(plain()), (StateMachine<Doc, DocStatus, DocTransition> machine) ->
                        machine.execute(plainDoc, SUBMIT)));
        Doc automaticDoc = new Doc("2", DRAFT);
        assertEquals(List.of("failed with the backend's Error in AUTOMATIC_STEPS, ALL"), terminalEvents(" end code=",
                readable(docs(TransitionBuilder::automatic)), (StateMachine<Doc, DocStatus, DocTransition> machine) ->
                        machine.execute(automaticDoc, SUBMIT)));
        assertEquals(List.of(REVIEW, REVIEW), List.of(plainDoc.status(), automaticDoc.status()));
    }

    /** A new builder of the documents machine over {@code graph}, which reads and writes the status. */
    private static StateMachines.Builder<Doc, DocStatus, DocTransition> readable(
            GraphDefinition<Doc, DocStatus, DocTransition> graph) {
        return StateMachines.from(graph).state(Doc::status, Doc::setStatus).identity(Doc::id);
    }

    /**
     * Runs {@code call} on {@code machine}, in a registry with an observer, while the backend throws an Error on every
     * line that contains {@code event}; asserts that the call throws that Error, and returns the end events the
     * observer heard.
     */
    private static List<String> terminalEvents(String event,
                                               StateMachines.Builder<Doc, DocStatus, DocTransition> machine,
                                               Function<StateMachine<Doc, DocStatus, DocTransition>, Object> call) {
        Fatal broken = new Fatal("handler invariant");
        Handler failing = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getMessage().contains(event)) {
                    throw broken;
                }
            }

            @Override
            public void flush() { }

            @Override
            public void close() { }
        };
        List<String> heard = new ArrayList<>();
        MachineObserver observer = new MachineObserver() {
            @Override
            public void onCompleted(MachineCall call, Outcome<?, ?, ?> outcome, Duration elapsed) {
                heard.add("completed");
            }

            @Override
            public void onFailed(MachineCall call, CallFailure failure, Duration elapsed) {
                String with = failure.error() == broken ? "the backend's Error" : "another";
                heard.add("failed with " + with + " in " + failure.phase() + ", " + failure.statesWritten());
            }
        };
        Logger runtime = Logger.getLogger(CallLog.LOGGER_NAME);
        try (MachineRegistry registry = MachineRegistry.builder().add(machine).observer(observer).build();
             LibraryLog log = LibraryLog.runtime(Level.FINE)) {
            runtime.addHandler(failing);
            try {
                assertSame(broken, assertThrows(Fatal.class, () -> call.apply(registry.of(DOCS))));
            } finally {
                runtime.removeHandler(failing);
            }
            List<LogRecord> records = log.records();
            assertTrue(records.get(records.size() - 1).getMessage().contains(event), records.toString());
        }
        return heard;
    }
}
