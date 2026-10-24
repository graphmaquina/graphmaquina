package io.github.graphmaquina.test.probe;

import io.github.graphmaquina.test.GraphAssertionError;
import io.github.graphmaquina.test.internal.Checks;
import java.util.ArrayList;
import java.util.List;
import lombok.NonNull;

/**
 * The shared order in which several probes were entered. Probes built with the same trace append to it, so a
 * test can assert that the guards of an edge ran before its action and that the action ran before its
 * after-commit hook, across probes that otherwise know nothing about each other.
 *
 * <p>The trace records entry into a callback and nothing else. A name appears as soon as the probe was called,
 * whether its scripted response then returned, failed or threw, and whether or not the transition went on to
 * commit; it is not a record of successful work. Recording is safe from several threads and numbers the entries
 * in the order the trace received them.
 *
 * <p>Those numbers count from 0, like the positions of {@link #events()} and of every list a probe hands
 * out. The call ordinals of the scripted half count from 1 instead: the ordinal
 * {@link StateWriteProbe#failOnCall} arms, and the one a probe names when it runs out of scripted
 * responses. Both bases are consumer-visible and neither follows the other.
 *
 * <p>A probe adds its entry in the same step in which it records its call, so the entries of one probe keep
 * the order of that probe's own calls even when several threads invoke it.
 */
public final class CallTrace {

    private final List<TraceEvent> events = new ArrayList<>();
    private long nextSequence;

    private CallTrace() { }

    public static CallTrace create() {
        return new CallTrace();
    }

    /** The entries recorded so far, copied, so a later call does not change a list already handed out. */
    public List<TraceEvent> events() {
        synchronized (events) {
            return List.copyOf(events);
        }
    }

    /**
     * Expects exactly these probe names, in this order and with this multiplicity, and raises
     * {@link GraphAssertionError} otherwise. A name repeats once per invocation, so a probe entered twice is
     * named twice.
     */
    public CallTrace assertNamesExactly(@NonNull String... names) {
        List<String> expected = List.of(names);
        List<String> actual = new ArrayList<>();
        for (TraceEvent event : events()) {
            actual.add(event.name());
        }
        if (!expected.equals(actual)) {
            Checks.fail("call trace", "names", expected, List.copyOf(actual));
        }
        return this;
    }

    void record(@NonNull String name, @NonNull Object call) {
        synchronized (events) {
            events.add(new TraceEvent(nextSequence++, name, call));
        }
    }
}
