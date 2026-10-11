package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.CallFailure;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.Reaction;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The log of one call, written through {@code System.getLogger("io.github.graphmaquina.runtime")}, at DEBUG and
 * TRACE only.
 * <ul>
 *   <li>It asks the logger once, when the call opens it, whether each level is loggable, and every log point tests
 *       one of those two answers before it builds a line: a level raised during a call reaches the next call.</li>
 *   <li>A call that finds both levels off gets {@link #OFF}, which is shared and emits nothing, so the call allocates
 *       nothing for its log.</li>
 *   <li>Each line starts with {@code call=<id>}. The id comes from one counter that advances for every call that
 *       logs, at DEBUG, at TRACE or at both; it is 0 only for {@link #OFF}, which logs nothing. A TRACE line of a
 *       step carries its depth next; the TRACE lines of the commit, the hooks and the watch dispatch carry none.</li>
 *   <li>An entity is printed only as an {@link EntityRef} the engine already holds, through {@link #show}: a line
 *       never calls an identity function, a getter or an entity's own {@code toString()}, unless the application put
 *       the entity in the flow under a visible key, whose {@code flow-put} and {@code hook-flow-put} lines print the
 *       value's {@code toString()}. The enum constants a line names are printed by {@code name()}; a reason or an
 *       error is printed as it was given.</li>
 *   <li>A RuntimeException that the backend throws never reaches the call: a level it was asked about counts as off,
 *       and a line it was given is dropped. An Error propagates.</li>
 * </ul>
 */
final class CallLog {

    static final String LOGGER_NAME = "io.github.graphmaquina.runtime";
    private static final Logger LOGGER = System.getLogger(LOGGER_NAME);
    private static final AtomicLong IDS = new AtomicLong();
    /** The log of every call that finds both levels off: id 0, start 0, and it emits nothing. */
    static final CallLog OFF = new CallLog(LOGGER, false, false, 0, 0, Optional.empty());

    private final Logger logger;
    private final boolean debug;
    private final boolean trace;
    private final long id;
    private final long startNanos;
    /** The id of the call whose commit triggered this watch reaction, when that call logged. */
    private final Optional<Long> trigger;
    /** The root entity's reference, once the engine registered it; kept only by a call that logs at DEBUG. */
    private Optional<EntityRef> entity = Optional.empty();

    private CallLog(Logger logger, boolean debug, boolean trace, long id, long startNanos, Optional<Long> trigger) {
        this.logger = logger;
        this.debug = debug;
        this.trace = trace;
        this.id = id;
        this.startNanos = startNanos;
        this.trigger = trigger;
    }

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
        boolean debug = loggable(logger, Level.DEBUG);
        boolean trace = loggable(logger, Level.TRACE);
        if (!debug && !trace) {
            return OFF;
        }
        return new CallLog(logger, debug, trace, IDS.incrementAndGet(), System.nanoTime(), trigger);
    }

    /** Whether {@code level} is loggable; a backend that throws a RuntimeException is taken as saying no. */
    private static boolean loggable(Logger logger, Level level) {
        try {
            return logger.isLoggable(level);
        } catch (RuntimeException backend) {
            return false;
        }
    }

    boolean debug() { return debug; }

    boolean trace() { return trace; }

    /** Greater than 0 when this call logs, at DEBUG or TRACE; 0 for {@link #OFF}. */
    long id() { return id; }

    /** The {@code System.nanoTime()} read when the log opened, which {@code elapsed} counts from; 0 for OFF. */
    long startNanos() { return startNanos; }

    /** Logs {@code call=<id> } followed by {@code text} at DEBUG, if this call logs at DEBUG. */
    void debug(String text) {
        if (debug) {
            emit(Level.DEBUG, "call=" + id + " " + text);
        }
    }

    /** Logs {@code call=<id> depth=<depth> } followed by {@code text} at TRACE, if this call logs at TRACE. */
    void trace(int depth, String text) {
        if (trace) {
            emit(Level.TRACE, "call=" + id + " depth=" + depth + " " + text);
        }
    }

    /** Logs {@code call=<id> } followed by {@code text} at TRACE, if this call logs at TRACE. */
    void trace(String text) {
        if (trace) {
            emit(Level.TRACE, "call=" + id + " " + text);
        }
    }

    private void emit(Level level, String line) {
        try {
            logger.log(level, line);
        } catch (RuntimeException backend) {
            // a backend that fails never changes the call: the line is dropped
        }
    }

    /** {@code start <machine> <request>[ trigger=<id>]}: the identifier execute was asked for, or reevaluate. */
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

    private String entityField() {
        return entity.isPresent() ? " entity=" + show(entity.get()) : "";
    }

    private String elapsed() {
        return micros(System.nanoTime() - startNanos);
    }

    /**
     * {@code String.valueOf(value)}, {@code <toString returned null>} when its {@code toString()} answers null, or
     * {@code <toString threw <class name>>} when it throws a RuntimeException; an Error propagates.
     */
    static String show(Object value) {
        try {
            String text = String.valueOf(value);
            return text != null ? text : "<toString returned null>";
        } catch (RuntimeException e) {
            return "<toString threw " + e.getClass().getName() + ">";
        }
    }

    /** {@code nanos} as whole microseconds: {@code <n>us}. */
    static String micros(long nanos) {
        return nanos / 1000 + "us";
    }
}
