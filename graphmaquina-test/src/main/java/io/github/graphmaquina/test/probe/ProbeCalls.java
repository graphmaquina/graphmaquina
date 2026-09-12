package io.github.graphmaquina.test.probe;

import io.github.graphmaquina.test.GraphAssertionError;
import io.github.graphmaquina.test.internal.Checks;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The invocations one probe recorded, in the order it received them. A probe owns exactly one of these and
 * hands it out from {@code calls()}; only the probe appends to it. Recording is safe from several threads,
 * and every list this class returns is an immutable copy taken when it was asked for.
 *
 * @param <C> what the owning probe records per call: an {@link ActionCall}, a {@link GuardCall} or, for a
 *        hook, the entity it was handed
 */
public final class ProbeCalls<C> {

    private final String probeName;
    private final List<C> calls = new ArrayList<>();

    ProbeCalls(String probeName) {
        this.probeName = Objects.requireNonNull(probeName, "probeName");
    }

    /** The calls recorded so far, copied, so a later call does not change a list already handed out. */
    public List<C> snapshot() {
        synchronized (calls) {
            return List.copyOf(calls);
        }
    }

    /** Expects exactly this many invocations, raising {@link GraphAssertionError} otherwise. */
    public void assertCount(int expected) {
        List<C> recorded = snapshot();
        if (recorded.size() != expected) {
            Checks.fail(context(), "count", expected, recorded.size());
        }
    }

    /** The only invocation recorded, raising {@link GraphAssertionError} when there was not exactly one. */
    public C single() {
        List<C> recorded = snapshot();
        if (recorded.size() != 1) {
            Checks.fail(context(), "count", 1, recorded.size());
        }
        return recorded.get(0);
    }

    void record(C call) {
        Objects.requireNonNull(call, "call");
        synchronized (calls) {
            calls.add(call);
        }
    }

    private String context() {
        return "probe " + probeName + " calls";
    }
}
