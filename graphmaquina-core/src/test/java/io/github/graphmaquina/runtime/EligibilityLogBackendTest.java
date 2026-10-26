package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.DocGraphs.docs;
import static io.github.graphmaquina.fixtures.DocMachines.registry;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.Eligibility;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.RejectionKind;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.error.EligibilityCheckException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import io.github.graphmaquina.fixtures.Fatal;
import io.github.graphmaquina.fixtures.LibraryLog;
import io.github.graphmaquina.fixtures.Sneaky;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
 * at {@code FINER}. A backend that throws a RuntimeException, or a checked exception other than an
 * InterruptedException, loses the line and changes neither the verdict nor the failure of a check, on whichever line it
 * throws, the last guard's line included; an InterruptedException loses it too but interrupts the thread again, so
 * that a later wait of the check fails as interrupted; one that throws an Error ends the check with that very Error,
 * whatever line it throws on, and no line follows it. The verdict line and the failed line are logged once the check
 * released its reentrancy marker, so a backend may ask for a check from them.
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
     * {@link #run(MachineRegistry, Handler, String, Consumer)} on a registry of the doc machine whose SUBMIT edge has
     * the guard {@code hasTitle}.
     */
    private static List<LogRecord> run(Handler failing, String docId, Consumer<Scene> check) {
        try (MachineRegistry registry = registry(EligibilityLogTest.titled())) {
            return run(registry, failing, docId, check);
        }
    }

    /**
     * Runs {@code check} for the doc {@code docId} on {@code registry} while {@code failing} is a handler of the
     * runtime logger, after the capture's own, so the capture sees every line the backend throws on. Returns the
     * records of that run.
     */
    private static List<LogRecord> run(MachineRegistry registry, Handler failing, String docId,
                                       Consumer<Scene> check) {
        Logger runtime = Logger.getLogger(CallLog.LOGGER_NAME);
        try (LibraryLog log = LibraryLog.runtime(Level.FINER)) {
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

    /** A handler that throws {@code thrown}, whatever its type, for each record whose text contains {@code line}. */
    private static Handler sneaking(Throwable thrown, String line) {
        return handler((LogRecord record) -> {
            if (record.getMessage().contains(line)) {
                throw Sneaky.raise(thrown);
            }
        });
    }

    @Test
    void aBackendThatThrowsACheckedExceptionNeverChangesTheVerdict() {
        List<LogRecord> records = run(sneaking(new IOException("disk full"), "check="), "d1", (Scene scene) ->
                assertEquals(new Eligibility.Eligible<>(scene.doc(), DRAFT, SUBMIT), scene.check()));
        assertEquals(4, records.size());
    }

    @Test
    void aBackendThatThrowsACheckedExceptionNeverChangesAFailure() {
        run(sneaking(new IOException("disk full"), "check="), "d3", (Scene scene) -> {
            EligibilityCheckException error = assertThrows(EligibilityCheckException.class, scene::check);
            assertEquals(Optional.of("hasTitle"), error.guard());
            assertEquals("no title list", error.getCause().getMessage());
        });
    }

    /** A doc graph whose SUBMIT edge has the asynchronous guard {@code waits}, which never completes its stage. */
    private static GraphDefinition<Doc, DocStatus, DocTransition> waiting() {
        return docs((TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.asyncGuard("waits",
                (GuardContext<Doc, DocStatus, DocTransition> ctx) -> new CompletableFuture<GuardResult>()));
    }

    /**
     * An InterruptedException of the backend loses its line but interrupts the thread again, as for a call: the wait
     * of the check for its asynchronous guard then ends as interrupted, and the check fails with that
     * InterruptedException as the cause. The test clears the thread's flag.
     */
    @Test
    void anInterruptedExceptionOfTheBackendInterruptsTheThreadAgain() {
        try (MachineRegistry registry = registry(waiting(), Duration.ofSeconds(10))) {
            run(registry, sneaking(new InterruptedException("stop"), " start "), "d1", (Scene scene) -> {
                EligibilityCheckException error;
                boolean interrupted;
                try {
                    error = assertThrows(EligibilityCheckException.class, scene::check);
                } finally {
                    interrupted = Thread.interrupted();
                }
                assertTrue(interrupted);
                assertInstanceOf(InterruptedException.class, error.getCause());
                assertEquals(Optional.of("waits"), error.guard());
            });
        }
    }

    /**
     * A handler that, the first time it sees a record whose text contains {@code line}, asks for a check of d1 on
     * {@code registry}'s doc machine, and keeps the answer, or what refused it.
     */
    private static final class Reentering {

        private final MachineRegistry registry;
        private final String line;
        private final AtomicBoolean entered = new AtomicBoolean();
        private final AtomicReference<Object> result = new AtomicReference<>();

        Reentering(MachineRegistry registry, String line) {
            this.registry = registry;
            this.line = line;
        }

        Handler handler() {
            return EligibilityLogBackendTest.handler((LogRecord record) -> {
                if (record.getMessage().contains(line) && entered.compareAndSet(false, true)) {
                    result.set(attempt());
                }
            });
        }

        private Object attempt() {
            try {
                return registry.of(DOCS).eligibility(new Doc("d1", DRAFT), SUBMIT);
            } catch (RuntimeException refused) {
                return refused;
            }
        }

        Object result() {
            return result.get();
        }
    }

    /** The verdict line comes after the marker is released: a check asked for from it is not refused as reentrant. */
    @Test
    void theVerdictLineIsLoggedOnceTheMarkerIsReleased() {
        try (MachineRegistry registry = registry(EligibilityLogTest.titled())) {
            Reentering reentering = new Reentering(registry, " end verdict=");
            run(registry, reentering.handler(), "d1", Scene::check);
            assertInstanceOf(Eligibility.Eligible.class, reentering.result());
        }
    }

    /** The failed line comes after the marker is released, as the verdict line does. */
    @Test
    void theFailedLineIsLoggedOnceTheMarkerIsReleased() {
        try (MachineRegistry registry = registry(EligibilityLogTest.titled())) {
            Reentering reentering = new Reentering(registry, " failed error=");
            run(registry, reentering.handler(), "d3", (Scene scene) ->
                    assertThrows(EligibilityCheckException.class, scene::check));
            assertInstanceOf(Eligibility.Eligible.class, reentering.result());
        }
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
