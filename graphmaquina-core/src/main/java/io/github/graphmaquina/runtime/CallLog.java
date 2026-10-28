package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.CallFailure;
import io.github.graphmaquina.api.Eligibility;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.Reaction;
import io.github.graphmaquina.error.EligibilityCheckException;
import io.github.graphmaquina.internal.Interrupts;
import io.github.graphmaquina.internal.Texts;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The log of one call or of one eligibility check, written through
 * {@code System.getLogger("io.github.graphmaquina.runtime")}, at DEBUG and TRACE only.
 * <ul>
 *   <li>It asks the logger once, when the call or the check opens it, whether each level is loggable, and every log
 *       point tests one of those two answers before it builds a line: a level raised during a call reaches the next
 *       call.</li>
 *   <li>A call or a check that finds both levels off gets {@link #OFF}, which is shared and emits nothing, so it
 *       allocates nothing for its log.</li>
 *   <li>Each line starts with {@code call=<id>}, or with {@code check=<id>} for an eligibility check, which is no
 *       call and logs only the lines of {@link #start}, {@link #checked} and {@link #checkFailed} and, at TRACE,
 *       those of the step it admits. The id comes from one counter, shared by calls and checks, that advances for
 *       every call or check that logs, at DEBUG, at TRACE or at both; it is 0 only for {@link #OFF}, which logs
 *       nothing, so no {@code call=<n>} and {@code check=<n>} line ever share a number. A TRACE line of a step
 *       carries its depth next; the TRACE lines of the commit, the hooks and the watch dispatch carry none.</li>
 *   <li>An entity is printed only as an {@link EntityRef} the engine already holds, through {@link #show}: a line
 *       never calls an identity function, a getter or an entity's own {@code toString()}, unless the application put
 *       the entity in the flow under a visible key, whose {@code flow-put} and {@code hook-flow-put} lines print the
 *       value's {@code toString()}. The enum constants a line names are printed by {@code name()}; a reason or an
 *       error is printed as it was given.</li>
 *   <li>Anything but an Error that the backend throws, a checked exception included, never reaches the call: a level
 *       it was asked about counts as off, and a line it was given is dropped. An InterruptedException interrupts the
 *       thread again. An Error propagates.</li>
 * </ul>
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
final class CallLog {

    static final String LOGGER_NAME = "io.github.graphmaquina.runtime";
    private static final Logger LOGGER = System.getLogger(LOGGER_NAME);
    private static final AtomicLong IDS = new AtomicLong();
    /** The label of the lines of a call. */
    private static final String CALL = "call";
    /** The label of the lines of an eligibility check. */
    private static final String CHECK = "check";
    /**
     * The log of every call or check that finds both levels off: id 0, start 0, and it emits nothing, so its label
     * is never printed.
     */
    static final CallLog OFF = new CallLog(LOGGER, false, false, 0, 0, Optional.empty(), CALL);

    private final Logger logger;
    @Getter(AccessLevel.PACKAGE)
    private final boolean debug;
    @Getter(AccessLevel.PACKAGE)
    private final boolean trace;

    /** Greater than 0 when this call or check logs, at DEBUG or TRACE; 0 for {@link #OFF}. */
    @Getter(AccessLevel.PACKAGE)
    private final long id;

    /** The {@code System.nanoTime()} read when the log opened, which {@code elapsed} counts from; 0 for OFF. */
    @Getter(AccessLevel.PACKAGE)
    private final long startNanos;
    /** The id of the call whose commit triggered this watch reaction, when that call logged. */
    private final Optional<Long> trigger;
    /** What starts every line before its id: {@code call}, or {@code check} for an eligibility check. */
    private final String label;
    /** The root entity's reference, once the engine registered it; kept only by a call that logs at DEBUG. */
    private Optional<EntityRef> entity = Optional.empty();

    /** Opens the log of a call on the runtime logger; {@code trigger} as in {@link #open(Logger, Optional)}. */
    static CallLog open(Optional<Long> trigger) {
        return open(LOGGER, trigger);
    }

    /**
     * Opens the log of a call on {@code logger}; tests hand in one of their own. {@code trigger} is the id of the
     * call whose commit triggered this watch reaction, empty for a call the application made. It reads both levels
     * once, and then the clock, unless both are off.
     */
    static CallLog open(Logger logger, Optional<Long> trigger) {
        return open(logger, trigger, CALL);
    }

    /**
     * Opens the log of an eligibility check on the runtime logger. Its lines start with {@code check=<id>}, and its id
     * comes from the counter of the calls.
     */
    static CallLog openCheck() {
        return openCheck(LOGGER);
    }

    /** Opens the log of an eligibility check on {@code logger}; tests hand in one of their own. */
    static CallLog openCheck(Logger logger) {
        return open(logger, Optional.empty(), CHECK);
    }

    private static CallLog open(Logger logger, Optional<Long> trigger, String label) {
        boolean debug = loggable(logger, Level.DEBUG);
        boolean trace = loggable(logger, Level.TRACE);
        if (!debug && !trace) {
            return OFF;
        }
        return new CallLog(logger, debug, trace, IDS.incrementAndGet(), System.nanoTime(), trigger, label);
    }

    /** Whether {@code level} is loggable; a backend that throws anything but an Error is taken as saying no. */
    private static boolean loggable(Logger logger, Level level) {
        try {
            return logger.isLoggable(level);
        } catch (Error fatal) {
            throw fatal;
        } catch (Throwable backend) {
            Interrupts.keep(backend);
            return false;
        }
    }

    /**
     * Logs {@code call=<id> } followed by {@code text} at DEBUG, if this call logs at DEBUG; {@code check=} in place
     * of {@code call=} for a check.
     */
    void debug(String text) {
        if (debug) {
            emit(Level.DEBUG, label + "=" + id + " " + text);
        }
    }

    /**
     * Logs {@code call=<id> depth=<depth> } followed by {@code text} at TRACE, if this call logs at TRACE;
     * {@code check=} in place of {@code call=} for a check.
     */
    void trace(int depth, String text) {
        if (trace) {
            emit(Level.TRACE, label + "=" + id + " depth=" + depth + " " + text);
        }
    }

    /**
     * Logs {@code call=<id> } followed by {@code text} at TRACE, if this call logs at TRACE; {@code check=} in place
     * of {@code call=} for a check.
     */
    void trace(String text) {
        if (trace) {
            emit(Level.TRACE, label + "=" + id + " " + text);
        }
    }

    private void emit(Level level, String line) {
        try {
            logger.log(level, line);
        } catch (Error fatal) {
            throw fatal;
        } catch (Throwable backend) {
            // a backend that fails never changes the call: the line is dropped
            Interrupts.keep(backend);
        }
    }

    /**
     * {@code start <machine> <request>[ trigger=<id>]}: the identifier execute or an eligibility check was asked for,
     * reevaluate or advance.
     */
    void start(MachineKey<?, ?, ?> machine, String request) {
        debug("start " + machine + " " + request + (trigger.isPresent() ? " trigger=" + trigger.get() : ""));
    }

    /** Keeps the reference the engine registered for the root entity, which the end and failed lines print. */
    void entity(EntityRef ref) {
        if (debug) {
            entity = Optional.of(ref);
        }
    }

    /**
     * One {@code committed} line per hop and one {@code started} line per start of a sub-machine, in log order:
     * {@code committed <ref> <from> -<T>-> <to> depth=<d>} and
     * {@code started <ref> <previous> -> <initial> parent=<ref> node=<node> depth=<d>}.
     */
    void committed(List<LogEntry> entries) {
        for (int index = 0; index < entries.size(); index++) {
            switch (entries.get(index)) {
                case LogEntry.Hop hop -> debug("committed " + show(hop.ref()) + " " + hop.from().name() + " -"
                        + hop.spec().transition().name() + "-> " + hop.to().name() + " depth=" + hop.depth());
                case LogEntry.Start start -> debug("started " + show(start.ref()) + " " + start.previous().name()
                        + " -> " + start.initial().name() + " parent=" + show(start.parent()) + " node="
                        + start.node().name() + " depth=" + start.depth());
            }
        }
    }

    /**
     * {@code end code=<code> state=<recorded state>[ entity=<ref>] records=<n> starts=<n> reactions=<n> aborted=<n>
     * [ reason="<explanation>"] elapsed=<us>}, on one line; the reason is printed as given, unescaped.
     */
    void end(Outcome<?, ?, ?> outcome) {
        List<Reaction> reactions = outcome.reactions();
        int aborted = 0;
        for (int index = 0; index < reactions.size(); index++) {
            if (reactions.get(index) instanceof Reaction.Aborted) {
                aborted++;
            }
        }
        Optional<String> reason = outcome.explanation();
        debug("end code=" + outcome.code().name() + " state=" + outcome.recordedState().name() + entityField()
                + " records=" + outcome.records().size() + " starts=" + outcome.starts().size() + " reactions="
                + reactions.size() + " aborted=" + aborted
                + (reason.isPresent() ? " reason=\"" + reason.get() + "\"" : "") + " elapsed=" + elapsed());
    }

    /** {@code failed phase=<phase> written=<states written>[ entity=<ref>] error=<error> elapsed=<us>}. */
    void failed(CallFailure failure) {
        debug("failed phase=" + failure.phase().name() + " written=" + failure.statesWritten().name() + entityField()
                + " error=" + show(failure.error()) + " elapsed=" + elapsed());
    }

    /**
     * The verdict of an eligibility check, on one line. For an eligible one,
     * {@code end verdict=ELIGIBLE transition=<T> state=<state> entity=<ref> elapsed=<us>}; for an ineligible one,
     * {@code end verdict=INELIGIBLE transition=<T> kind=<kind>[ guard=<name>] state=<state> entity=<ref>
     * reason="<reason>" elapsed=<us>}, the reason printed as given, unescaped. {@code <T>} is the identifier the check
     * asked about, and the state is the one the check read.
     */
    void checked(Eligibility<?, ?, ?> verdict) {
        switch (verdict) {
            case Eligibility.Eligible<?, ?, ?> eligible -> debug("end verdict=ELIGIBLE transition="
                    + eligible.transition().name() + " state=" + eligible.from().name() + entityField()
                    + " elapsed=" + elapsed());
            case Eligibility.Ineligible<?, ?, ?> ineligible -> debug("end verdict=INELIGIBLE transition="
                    + ineligible.transition().name() + " kind=" + ineligible.kind().name()
                    + (ineligible.guard().isPresent() ? " guard=" + ineligible.guard().get() : "")
                    + " state=" + ineligible.from().name() + entityField() + " reason=\"" + ineligible.reason()
                    + "\" elapsed=" + elapsed());
        }
    }

    /** {@code failed error=<error> elapsed=<us>}: the {@code EligibilityCheckException} the check throws. */
    void checkFailed(EligibilityCheckException failure) {
        debug("failed error=" + show(failure) + " elapsed=" + elapsed());
    }

    private String entityField() {
        return entity.isPresent() ? " entity=" + show(entity.get()) : "";
    }

    private String elapsed() {
        return micros(System.nanoTime() - startNanos);
    }

    /**
     * {@code value.toString()}, {@code <toString returned null>} when that answers null, or
     * {@code <toString threw <class name>>} when it throws anything but an Error, a checked exception included, as
     * {@link Texts#shown} prints it, which interrupts the thread again for an InterruptedException; an Error
     * propagates.
     */
    static String show(Object value) {
        return Texts.shown(value);
    }

    /** {@code nanos} as whole microseconds: {@code <n>us}. */
    static String micros(long nanos) {
        return nanos / 1000 + "us";
    }
}
