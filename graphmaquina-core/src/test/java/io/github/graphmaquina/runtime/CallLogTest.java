package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.ARCHIVE;
import static io.github.graphmaquina.fixtures.DocTransition.PUBLISH;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.CallFailure;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.IgnoreReason;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.Reaction;
import io.github.graphmaquina.api.RejectionKind;
import io.github.graphmaquina.api.StatesWritten;
import io.github.graphmaquina.api.TransitionRecord;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The log of one call, without a machine: it reads both levels once, when it opens, and a call that finds both off
 * gets the shared {@link CallLog#OFF}, which advances no id and emits nothing. Each line starts with its call's id, a
 * step line at TRACE also with its depth, and the DEBUG lines print what the engine hands them in a fixed format. A
 * value whose {@code toString()} throws prints as a marker, and a backend that throws a RuntimeException never reaches
 * the call, while an Error does.
 */
class CallLogTest {

    /** A System.Logger with the levels a test enables, which records each line and can be made to throw. */
    static final class Backend implements System.Logger {

        final Set<Level> enabled = EnumSet.noneOf(Level.class);
        final List<String> lines = new ArrayList<>();
        Optional<Throwable> failsOnCheck = Optional.empty();
        Optional<Throwable> failsOnLog = Optional.empty();

        Backend(Level... levels) {
            enabled.addAll(List.of(levels));
        }

        private static void raise(Optional<Throwable> failure) {
            if (failure.isPresent()) {
                Throwable thrown = failure.get();
                if (thrown instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw (Error) thrown;
            }
        }

        @Override
        public String getName() {
            return CallLog.LOGGER_NAME;
        }

        @Override
        public boolean isLoggable(Level level) {
            raise(failsOnCheck);
            return enabled.contains(level);
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String message, Throwable thrown) {
            raise(failsOnLog);
            lines.add(level + " " + message);
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String format, Object... params) {
            raise(failsOnLog);
            lines.add(level + " " + format);
        }
    }

    /** An identity value whose {@code toString()} throws. */
    private record BrokenId(String id) {
        @Override
        public String toString() {
            throw new IllegalStateException("no text for " + id);
        }
    }

    /** DRAFT -SUBMIT-> REVIEW -PUBLISH-> PUBLISHED -ARCHIVE-> ARCHIVED. */
    private static GraphDefinition<Doc, DocStatus, DocTransition> graph() {
        return StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted").statePolicy(REVIEW, "a review is published")
                .statePolicy(PUBLISHED, "a publication is archived")
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
    }

    private static EntityRef ref(Doc doc) {
        return new EntityRef(DOCS, doc.id(), doc);
    }

    /** {@code line} without its {@code elapsed=<n>us} ending, which it must have. */
    private static String withoutElapsed(String line) {
        assertTrue(line.matches(".* elapsed=\\d+us"), line);
        return line.substring(0, line.lastIndexOf(" elapsed="));
    }

    @Test
    void theLoggerIsTheRuntimeOne() {
        assertEquals("io.github.graphmaquina.runtime", CallLog.LOGGER_NAME);
    }

    @Test
    void bothLevelsOffGiveTheSharedOffLogWhichAdvancesNoIdAndEmitsNothing() {
        Backend debugOn = new Backend(Level.DEBUG);
        Backend off = new Backend(Level.INFO, Level.WARNING, Level.ERROR);
        long first = CallLog.open(debugOn, Optional.empty()).id();
        CallLog log = CallLog.open(off, Optional.of(3L));
        long second = CallLog.open(debugOn, Optional.empty()).id();
        assertSame(CallLog.OFF, log);
        assertEquals(first + 1, second);
        assertFalse(log.debug());
        assertFalse(log.trace());
        assertEquals(0, log.id());
        assertEquals(0, log.startNanos());
        log.debug("start Doc/DocStatus SUBMIT");
        log.trace(0, "guard Ready PASS elapsed=1us");
        log.trace("hook Notify");
        assertEquals(List.of(), off.lines);
        assertEquals(List.of(), debugOn.lines);
    }

    @Test
    void eachLineStartsWithTheCallIdAndATraceStepLineWithItsDepth() {
        Backend backend = new Backend(Level.DEBUG, Level.TRACE);
        CallLog log = CallLog.open(backend, Optional.empty());
        assertTrue(log.debug());
        assertTrue(log.trace());
        assertTrue(log.id() > 0);
        log.debug("start Doc/DocStatus SUBMIT");
        log.trace(2, "guard Ready PASS elapsed=1us");
        log.trace("hook Notify");
        assertEquals(List.of(
                "DEBUG call=" + log.id() + " start Doc/DocStatus SUBMIT",
                "TRACE call=" + log.id() + " depth=2 guard Ready PASS elapsed=1us",
                "TRACE call=" + log.id() + " hook Notify"), backend.lines);
    }

    @Test
    void aLevelThatIsOffEmitsNothing() {
        Backend debugOnly = new Backend(Level.DEBUG);
        CallLog debug = CallLog.open(debugOnly, Optional.empty());
        debug.trace(1, "action Send OK elapsed=1us");
        debug.trace("hook Notify");
        assertEquals(List.of(), debugOnly.lines);
        Backend traceOnly = new Backend(Level.TRACE);
        CallLog trace = CallLog.open(traceOnly, Optional.empty());
        assertNotSame(CallLog.OFF, trace);
        assertFalse(trace.debug());
        trace.debug("start Doc/DocStatus SUBMIT");
        trace.trace(1, "action Send OK elapsed=1us");
        assertEquals(List.of("TRACE call=" + trace.id() + " depth=1 action Send OK elapsed=1us"), traceOnly.lines);
    }

    /** The counter numbers every call that logs, at DEBUG or at TRACE alone: no line says {@code call=0}. */
    @Test
    void aCallThatLogsAtTraceAloneTakesAnIdFromTheSameCounter() {
        CallLog debug = CallLog.open(new Backend(Level.DEBUG), Optional.empty());
        CallLog trace = CallLog.open(new Backend(Level.TRACE), Optional.empty());
        CallLog next = CallLog.open(new Backend(Level.DEBUG), Optional.empty());
        assertTrue(trace.trace());
        assertFalse(trace.debug());
        assertTrue(trace.id() > 0);
        assertEquals(debug.id() + 1, trace.id());
        assertEquals(trace.id() + 1, next.id());
    }

    /** D16: a level change reaches only the calls that open after it. */
    @Test
    void theLevelsAreReadOnceWhenTheLogOpens() {
        Backend backend = new Backend(Level.DEBUG);
        CallLog open = CallLog.open(backend, Optional.empty());
        Backend later = new Backend();
        CallLog off = CallLog.open(later, Optional.empty());
        backend.enabled.clear();
        later.enabled.add(Level.DEBUG);
        open.debug("committed");
        off.debug("committed");
        assertEquals(List.of("DEBUG call=" + open.id() + " committed"), backend.lines);
        assertEquals(List.of(), later.lines);
    }

    @Test
    void showPrintsTheValueOrAMarkerWhenItsToStringThrows() {
        assertEquals("Doc/DocStatus#7", CallLog.show(new EntityRef(DOCS, "7", new Doc("7", DRAFT))));
        assertEquals("Doc/DocStatus#<toString threw java.lang.IllegalStateException>",
                CallLog.show(new EntityRef(DOCS, new BrokenId("7"), new Doc("7", DRAFT))));
        Object fatal = new Object() {
            @Override
            public String toString() {
                throw new AssertionError("broken toString");
            }
        };
        assertThrows(AssertionError.class, () -> CallLog.show(fatal));
    }

    /** A toString that answers null, against its contract, prints a marker, never null. */
    @Test
    void showPrintsAMarkerWhenAToStringAnswersNull() {
        Object silent = new Object() {
            @Override
            public String toString() {
                return null;
            }
        };
        assertEquals("<toString returned null>", CallLog.show(silent));
    }

    @Test
    void microsTruncatesToWholeMicroseconds() {
        assertEquals("0us", CallLog.micros(0));
        assertEquals("0us", CallLog.micros(999));
        assertEquals("1us", CallLog.micros(1_999));
        assertEquals("2000us", CallLog.micros(2_000_000));
    }

    @Test
    void aBackendRuntimeExceptionIsContained() {
        Backend backend = new Backend(Level.DEBUG, Level.TRACE);
        CallLog log = CallLog.open(backend, Optional.empty());
        backend.failsOnLog = Optional.of(new IllegalStateException("disk full"));
        assertDoesNotThrow(() -> log.debug("start Doc/DocStatus SUBMIT"));
        assertDoesNotThrow(() -> log.trace(0, "no-edge"));
        assertDoesNotThrow(() -> log.trace("hook Notify"));
        backend.failsOnCheck = Optional.of(new IllegalStateException("no configuration"));
        assertSame(CallLog.OFF, CallLog.open(backend, Optional.empty()));
    }

    /**
     * A check reads its levels as a call does: a backend that throws anything but an Error there counts as off, and an
     * Error propagates.
     */
    @Test
    void aCheckWhoseLevelsCannotBeReadGetsTheOffLog() {
        Backend backend = new Backend(Level.DEBUG, Level.TRACE);
        backend.failsOnCheck = Optional.of(new IllegalStateException("no configuration"));
        assertSame(CallLog.OFF, CallLog.openCheck(backend));
        AssertionError broken = new AssertionError("backend invariant");
        backend.failsOnCheck = Optional.of(broken);
        assertSame(broken, assertThrows(AssertionError.class, () -> CallLog.openCheck(backend)));
    }

    @Test
    void aBackendErrorPropagates() {
        Backend backend = new Backend(Level.DEBUG, Level.TRACE);
        CallLog log = CallLog.open(backend, Optional.empty());
        AssertionError broken = new AssertionError("backend invariant");
        backend.failsOnLog = Optional.of(broken);
        assertSame(broken, assertThrows(AssertionError.class, () -> log.debug("start Doc/DocStatus SUBMIT")));
        assertSame(broken, assertThrows(AssertionError.class, () -> log.trace(0, "no-edge")));
        assertSame(broken, assertThrows(AssertionError.class, () -> log.trace("hook Notify")));
        backend.failsOnCheck = Optional.of(broken);
        assertSame(broken, assertThrows(AssertionError.class, () -> CallLog.open(backend, Optional.empty())));
    }

    @Test
    void theStartLineNamesTheMachineTheRequestAndTheTrigger() {
        Backend backend = new Backend(Level.DEBUG);
        CallLog call = CallLog.open(backend, Optional.empty());
        CallLog reaction = CallLog.open(backend, Optional.of(call.id()));
        call.start(DOCS, "SUBMIT");
        reaction.start(DOCS, "reevaluate");
        assertEquals(List.of(
                "DEBUG call=" + call.id() + " start Doc/DocStatus SUBMIT",
                "DEBUG call=" + reaction.id() + " start Doc/DocStatus reevaluate trigger=" + call.id()),
                backend.lines);
    }

    @Test
    void theCommittedAndStartedLinesFollowTheLogInOrder() {
        Backend backend = new Backend(Level.DEBUG);
        CallLog log = CallLog.open(backend, Optional.empty());
        Doc parent = new Doc("1", DRAFT);
        Doc child = new Doc("2", ARCHIVED);
        TransitionSpec<Doc, DocStatus, DocTransition> submit = graph().findTransition(DRAFT, SUBMIT).orElseThrow();
        log.committed(List.of(new LogEntry.Hop(ref(parent), submit, 0, false),
                new LogEntry.Start(ref(child), ARCHIVED, DRAFT, ref(parent), REVIEW, 1)));
        assertEquals(List.of(
                "DEBUG call=" + log.id() + " committed Doc/DocStatus#1 DRAFT -SUBMIT-> REVIEW depth=0",
                "DEBUG call=" + log.id() + " started Doc/DocStatus#2 ARCHIVED -> DRAFT parent=Doc/DocStatus#1"
                        + " node=REVIEW depth=1"), backend.lines);
    }

    @Test
    void theEndLineCountsWhatTheOutcomeCarriesAndQuotesItsExplanation() {
        Backend backend = new Backend(Level.DEBUG);
        Doc doc = new Doc("1", REVIEW);
        CallLog rejected = CallLog.open(backend, Optional.empty());
        rejected.end(new Outcome.Rejected<>(doc, REVIEW, SUBMIT, "a review is published", RejectionKind.NO_TRANSITION,
                Optional.empty()));
        CallLog applied = CallLog.open(backend, Optional.empty());
        applied.entity(ref(doc));
        TransitionRecord<Doc, DocStatus, DocTransition> hop = Typed.record(DOCS,
                new LogEntry.Hop(ref(doc), graph().findTransition(REVIEW, PUBLISH).orElseThrow(), 0, false));
        Outcome<Doc, DocStatus, DocTransition> done = new Outcome.Ignored<>(doc, PUBLISHED, IgnoreReason.NO_WATCH);
        applied.end(new Outcome.Applied<>(doc, REVIEW, PUBLISH, PUBLISHED, List.of(hop),
                List.of(new Reaction.Done(DOCS, doc, done),
                        new Reaction.Aborted(DOCS, doc, new IllegalStateException("x")))));
        assertEquals(List.of(
                "DEBUG call=" + rejected.id() + " end code=NO_TRANSITION state=REVIEW records=0 starts=0 reactions=0"
                        + " aborted=0 reason=\"a review is published\"",
                "DEBUG call=" + applied.id() + " end code=APPLIED state=PUBLISHED entity=Doc/DocStatus#1 records=1"
                        + " starts=0 reactions=2 aborted=1"),
                backend.lines.stream().map(CallLogTest::withoutElapsed).toList());
    }

    @Test
    void theFailedLineNamesThePhaseTheStatesWrittenAndTheError() {
        Backend backend = new Backend(Level.DEBUG);
        CallLog log = CallLog.open(backend, Optional.empty());
        log.entity(new EntityRef(DOCS, new BrokenId("1"), new Doc("1", DRAFT)));
        log.failed(new CallFailure(CallPhase.COMMIT, StatesWritten.PARTIAL, new IllegalStateException("disk full")));
        assertEquals(List.of("DEBUG call=" + log.id() + " failed phase=COMMIT written=PARTIAL"
                        + " entity=Doc/DocStatus#<toString threw java.lang.IllegalStateException>"
                        + " error=java.lang.IllegalStateException: disk full"),
                backend.lines.stream().map(CallLogTest::withoutElapsed).toList());
    }
}
