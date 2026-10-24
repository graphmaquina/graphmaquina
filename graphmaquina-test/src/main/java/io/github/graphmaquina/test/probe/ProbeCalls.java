package io.github.graphmaquina.test.probe;

import io.github.graphmaquina.test.GraphAssertionError;
import io.github.graphmaquina.test.internal.Checks;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The invocations one probe recorded, in the order it received them. A probe owns exactly one of these and
 * hands it out from {@code calls()}; only the probe appends to it. Recording is safe from several threads,
 * and every list this class returns is an immutable copy taken when it was asked for.
 *
 * <p>The positions of that list count from 0. The call ordinal the owning probe names when it runs out of
 * scripted responses counts from 1, as {@link StateWriteProbe#failOnCall} does, so the same invocation is
 * position 1 here and call 2 there. That holds when several threads call the probe at once, because the probe
 * records a call and withdraws its ordinal in one step.
 *
 * @param <C> what the owning probe records per call: an {@link ActionCall}, a {@link GuardCall} or, for a
 *        hook, the entity it was handed
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public final class ProbeCalls<C> {

    @NonNull
    private final String probeName;
    private final List<C> calls = new ArrayList<>();

    /** The calls recorded so far, copied, so a later call does not change a list already handed out. */
    public List<C> snapshot() {
        synchronized (calls) {
            return List.copyOf(calls);
        }
    }

    /**
     * Expects exactly this many invocations, raising {@link GraphAssertionError} otherwise. It returns
     * nothing on purpose: a probe's calls end a chain. The state setter's counterpart,
     * {@link StateWriteProbe#assertCount(int)}, returns its probe instead, because writes do keep chaining.
     */
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

    void record(@NonNull C call) {
        synchronized (calls) {
            calls.add(call);
        }
    }

    private String context() {
        return "probe " + probeName + " calls";
    }
}
