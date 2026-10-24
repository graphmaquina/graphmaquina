package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.CallFailure;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.IgnoreReason;
import io.github.graphmaquina.api.MachineCall;
import io.github.graphmaquina.api.MachineObserver;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.StatesWritten;
import io.github.graphmaquina.error.ReentrantTransitionException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;
import lombok.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What the registry's observers are notified with, without a machine: an exception an observer throws is logged as a
 * warning naming the observer, the callback and the call but never the entity, the next observer is still notified,
 * an Error propagates, the reentrancy marker is set during each callback and released after it, and the end events
 * measure from the clock read the call hands in.
 */
class ObserversTest {

    /** A warning backend that fails either its level check or its write. */
    static final class BrokenLogger implements System.Logger {
        private final boolean checking;
        private final Throwable failure;

        BrokenLogger(boolean checking, @NonNull Throwable failure) {
            this.checking = checking;
            this.failure = failure;
        }

        private void fail() {
            if (failure instanceof Error fatal) {
                throw fatal;
            }
            throw (RuntimeException) failure;
        }

        @Override public String getName() { return "broken"; }

        @Override public boolean isLoggable(Level level) {
            if (checking) {
                fail();
            }
            return true;
        }

        @Override public void log(Level level, ResourceBundle bundle, String message, Throwable thrown) { fail(); }
        @Override public void log(Level level, ResourceBundle bundle, String format, Object... params) { fail(); }
    }

    /** A System.Logger that keeps what it was asked to log. */
    static final class RecordingLogger implements System.Logger {

        final List<String> records = new ArrayList<>();

        @Override
        public String getName() {
            return "recording";
        }

        @Override
        public boolean isLoggable(Level level) {
            return true;
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String message, Throwable thrown) {
            records.add(level + ": " + message + " | " + thrown);
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String format, Object... params) {
            records.add(level + ": " + format);
        }
    }

    /** Throws the same exception from every callback. */
    static final class Failing implements MachineObserver {

        private final RuntimeException failure;

        Failing(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public void onStarted(MachineCall call) {
            throw failure;
        }

        @Override
        public void onCompleted(MachineCall call, Outcome<?, ?, ?> outcome, Duration elapsed) {
            throw failure;
        }

        @Override
        public void onFailed(MachineCall call, CallFailure failure, Duration elapsed) {
            throw this.failure;
        }
    }

    private static Outcome<Doc, DocStatus, DocTransition> ignored(Doc entity) {
        return new Outcome.Ignored<Doc, DocStatus, DocTransition>(entity, DRAFT, IgnoreReason.NO_WATCH);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aNonfatalWarningBackendFailureStillNotifiesTheOtherObservers(boolean checking) {
        List<String> after = new ArrayList<>();
        MachineObserver next = new MachineObserver() {
            @Override public void onStarted(MachineCall call) { after.add("started"); }
            @Override public void onCompleted(MachineCall call, Outcome<?, ?, ?> outcome, Duration elapsed) {
                after.add("completed");
            }
            @Override public void onFailed(MachineCall call, CallFailure failure, Duration elapsed) {
                after.add("failed");
            }
        };
        Doc entity = new Doc("1", DRAFT);
        MachineCall call = new MachineCall(DOCS, entity, Optional.of(SUBMIT), false);
        Observed observed = new Observers(List.of(new Failing(new IllegalStateException("observer failed")), next),
                new BrokenLogger(checking, new IllegalStateException("warning backend failed")))
                .started(call, System.nanoTime());
        observed.completed(ignored(entity));
        observed.failed(new CallFailure(CallPhase.ACTION, StatesWritten.NONE, new IllegalArgumentException("x")));
        assertEquals(List.of("started", "completed", "failed"), after);
        Reentrancy.check(DOCS, entity);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aFatalWarningBackendFailurePropagatesAndReleasesTheMarker(boolean checking) {
        AssertionError fatal = new AssertionError("warning backend failed");
        List<String> after = new ArrayList<>();
        MachineObserver next = new MachineObserver() {
            @Override public void onStarted(MachineCall call) { after.add("started"); }
        };
        Doc entity = new Doc("1", DRAFT);
        MachineCall call = new MachineCall(DOCS, entity, Optional.of(SUBMIT), false);
        Observers observers = new Observers(List.of(new Failing(new IllegalStateException("observer failed")), next),
                new BrokenLogger(checking, fatal));
        assertSame(fatal, assertThrows(AssertionError.class, () -> observers.started(call, System.nanoTime())));
        assertEquals(List.of(), after);
        Reentrancy.check(DOCS, entity);
    }

    @Test
    void anObserverExceptionIsLoggedAsAWarningThatNamesTheObserverTheCallbackAndTheCall() {
        IllegalStateException boom = new IllegalStateException("metrics down");
        RecordingLogger logger = new RecordingLogger();
        List<String> after = new ArrayList<>();
        MachineObserver next = new MachineObserver() {
            @Override
            public void onFailed(MachineCall call, CallFailure failure, Duration elapsed) {
                after.add("notified");
            }
        };
        Doc entity = new Doc("secret", DRAFT);
        MachineCall call = new MachineCall(DOCS, entity, Optional.of(SUBMIT), false);
        Observed observed = new Observers(List.of(new Failing(boom), next), logger).started(call, System.nanoTime());
        observed.completed(ignored(entity));
        observed.failed(new CallFailure(CallPhase.ACTION, StatesWritten.NONE, new IllegalArgumentException("x")));
        String prefix = "WARNING: the observer " + Failing.class.getName() + " threw in ";
        assertEquals(List.of(
                prefix + "onStarted for Doc/DocStatus SUBMIT | " + boom,
                prefix + "onCompleted for Doc/DocStatus SUBMIT | " + boom,
                prefix + "onFailed for Doc/DocStatus SUBMIT | " + boom), logger.records);
        assertFalse(logger.records.toString().contains("secret"));
        assertEquals(List.of("notified"), after);
    }

    /** The end events measure from the clock read the call hands in, which a call that logs shares with its log. */
    @Test
    void theEndEventsMeasureFromTheClockReadTheCallHandsIn() {
        Doc entity = new Doc("1", DRAFT);
        List<Duration> elapsed = new ArrayList<>();
        MachineObserver observer = new MachineObserver() {
            @Override
            public void onCompleted(MachineCall call, Outcome<?, ?, ?> outcome, Duration time) {
                elapsed.add(time);
            }
        };
        long startNanos = System.nanoTime() - Duration.ofHours(1).toNanos();
        Observed observed = new Observers(List.of(observer), new RecordingLogger())
                .started(new MachineCall(DOCS, entity, Optional.of(SUBMIT), false), startNanos);
        observed.completed(ignored(entity));
        assertEquals(startNanos, observed.startNanos());
        assertTrue(elapsed.get(0).compareTo(Duration.ofHours(1)) >= 0, elapsed.toString());
    }

    @Test
    void theReentrancyMarkerIsSetDuringEachCallbackAndReleasedAfterIt() {
        Doc entity = new Doc("1", DRAFT);
        List<Class<?>> during = new ArrayList<>();
        MachineObserver probe = new MachineObserver() {
            @Override
            public void onStarted(MachineCall call) {
                during.add(assertThrows(ReentrantTransitionException.class,
                        () -> Reentrancy.check(DOCS, entity)).getClass());
            }
        };
        new Observers(List.of(probe), new RecordingLogger())
                .started(new MachineCall(DOCS, entity, Optional.of(SUBMIT), false), System.nanoTime());
        assertEquals(List.of(ReentrantTransitionException.class), during);
        Reentrancy.check(DOCS, entity);
    }

    @Test
    void anErrorFromAnObserverPropagatesAndReleasesTheMarker() {
        AssertionError broken = new AssertionError("observer invariant");
        Doc entity = new Doc("1", DRAFT);
        MachineObserver observer = new MachineObserver() {
            @Override
            public void onCompleted(MachineCall call, Outcome<?, ?, ?> outcome, Duration elapsed) {
                throw broken;
            }
        };
        RecordingLogger logger = new RecordingLogger();
        Observed observed = new Observers(List.of(observer), logger)
                .started(new MachineCall(DOCS, entity, Optional.of(SUBMIT), false), System.nanoTime());
        assertSame(broken, assertThrows(AssertionError.class, () -> observed.completed(ignored(entity))));
        assertEquals(List.of(), logger.records);
        Reentrancy.check(DOCS, entity);
    }
}
