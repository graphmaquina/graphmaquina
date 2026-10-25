package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.DocMachines.registry;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.Eligibility;
import io.github.graphmaquina.api.RejectionKind;
import io.github.graphmaquina.error.EligibilityCheckException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import io.github.graphmaquina.fixtures.Fatal;
import io.github.graphmaquina.fixtures.LibraryLog;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The lines of an eligibility check when the backend that writes them throws, read through {@code java.util.logging}
 * at {@code FINER}. A backend that throws a RuntimeException changes neither the verdict nor the failure of a check,
 * on whichever line it throws, the last guard's line included; one that throws an Error ends the check with that very
 * Error, whatever line it throws on, and no line follows it.
 */
class EligibilityLogBackendTest {

    /** A handler that runs {@code onPublish} for each record and has nothing else to do. */
    private static Handler handler(Consumer<LogRecord> onPublish) {
        return new Handler() {
            @Override
            public void publish(LogRecord record) {
                onPublish.accept(record);
            }

            @Override
            public void flush() { }

            @Override
            public void close() { }
        };
    }

    /** A doc and the registry whose doc machine a check asks about it. */
    private record Scene(MachineRegistry registry, Doc doc) {

        Eligibility<Doc, DocStatus, DocTransition> check() {
            return registry.of(DOCS).eligibility(doc, SUBMIT);
        }
    }

    /**
     * Runs {@code check} for the doc {@code docId} while {@code failing} is a handler of the runtime logger, after the
     * capture's own, so the capture sees every line the backend throws on. Returns the records of that run.
     */
    private static List<LogRecord> run(Handler failing, String docId, Consumer<Scene> check) {
        Logger runtime = Logger.getLogger(CallLog.LOGGER_NAME);
        try (MachineRegistry registry = registry(EligibilityLogTest.titled());
             LibraryLog log = LibraryLog.runtime(Level.FINER)) {
            runtime.addHandler(failing);
            try {
                check.accept(new Scene(registry, new Doc(docId, DRAFT)));
            } finally {
                runtime.removeHandler(failing);
            }
            return log.records();
        }
    }

    /** A handler that throws a RuntimeException for every record. */
    private static Handler diskFull() {
        return handler((LogRecord record) -> {
            throw new IllegalStateException("disk full");
        });
    }

    @Test
    void aBackendThatThrowsARuntimeExceptionNeverChangesTheVerdict() {
        List<LogRecord> records = run(diskFull(), "d1", (Scene scene) ->
                assertEquals(new Eligibility.Eligible<>(scene.doc(), DRAFT, SUBMIT), scene.check()));
        assertEquals(4, records.size());
    }

    @Test
    void aBackendThatThrowsARuntimeExceptionNeverChangesARejection() {
        run(diskFull(), "d2", (Scene scene) ->
                assertEquals(new Eligibility.Ineligible<>(scene.doc(), DRAFT, SUBMIT, EligibilityLogTest.NEEDS_TITLE,
                        RejectionKind.GUARD, Optional.of("hasTitle")), scene.check()));
    }

    @Test
    void aBackendThatThrowsARuntimeExceptionNeverChangesAFailure() {
        run(diskFull(), "d3", (Scene scene) -> {
            EligibilityCheckException error = assertThrows(EligibilityCheckException.class, scene::check);
            assertEquals(Optional.of("hasTitle"), error.guard());
            assertEquals("no title list", error.getCause().getMessage());
        });
    }

    /**
     * An Error of the backend ends the check with that very Error, on its start line, on the line of its guard, on
     * its verdict line and on the failed line of a check that throws; the lines logged up to it stay, and none
     * follows it.
     */
    @ParameterizedTest
    @CsvSource({"start, d1, 1", "guard, d1, 3", "end, d1, 4", "failed, d3, 3"})
    void aBackendErrorPropagatesAsItself(String line, String docId, int logged) {
        Fatal broken = new Fatal("handler invariant");
        Handler failing = handler((LogRecord record) -> {
            if (record.getMessage().contains(" " + line + " ")) {
                throw broken;
            }
        });
        List<LogRecord> records = run(failing, docId, (Scene scene) ->
                assertSame(broken, assertThrows(Fatal.class, scene::check)));
        assertEquals(logged, records.size());
    }
}
