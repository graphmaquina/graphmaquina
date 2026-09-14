package io.github.graphmaquina.test.probe;

import io.github.graphmaquina.test.GraphAssertionError;
import io.github.graphmaquina.test.internal.ResponseScript;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A {@link Consumer} for the after-commit hook of a cell, scripted like {@link ActionProbe} but with nothing to
 * return: a scripted response either succeeds, from {@link #thenSucceed} or {@link #otherwiseSucceed}, or
 * throws the exception given to {@link #thenThrow}. The hook is recorded in the probe's own {@link ProbeCalls}
 * — which holds the entities themselves — and in the shared {@link CallTrace}, and a call the script no longer
 * covers raises a {@link GraphAssertionError} naming the probe and the ordinal of the call.
 *
 * <p>A recorded call proves this probe ran. Its absence alone cannot determine whether the state committed:
 * state writes happen before after-commit hooks, and an earlier hook can stop subsequent hooks.
 *
 * @param <E> the entity type of the machine the hook belongs to
 */
public final class HookProbe<E> implements Consumer<E> {

    private final String name;
    private final CallTrace trace;
    private final ProbeCalls<E> calls;
    private final ResponseScript<Boolean> script;
    private volatile Consumer<E> onCall = entity -> { };

    private HookProbe(String name, CallTrace trace) {
        this.name = name;
        this.trace = trace;
        this.calls = new ProbeCalls<>(name);
        this.script = new ResponseScript<>(name);
    }

    /** The name must be the one the graph gives this hook, since that is what the trace reports. */
    public static <E> HookProbe<E> create(String name, CallTrace trace) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(trace, "trace");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        return new HookProbe<>(name, trace);
    }

    /** Queues one successful call, answered after every response queued before it. */
    public HookProbe<E> thenSucceed() {
        script.then(() -> Boolean.TRUE);
        return this;
    }

    /** Queues one exception, thrown by identity: the call throws this very instance. */
    public HookProbe<E> thenThrow(RuntimeException error) {
        Objects.requireNonNull(error, "error");
        script.then(() -> { throw error; });
        return this;
    }

    /** Makes every call the queue no longer covers succeed. */
    public HookProbe<E> otherwiseSucceed() {
        script.otherwise(() -> Boolean.TRUE);
        return this;
    }

    /** Replaces the side effect run on every call, with the entity the hook was handed. */
    public HookProbe<E> onCall(Consumer<E> onCall) {
        this.onCall = Objects.requireNonNull(onCall, "onCall");
        return this;
    }

    public ProbeCalls<E> calls() {
        return calls;
    }

    @Override
    public void accept(E entity) {
        calls.record(entity);
        trace.record(name, entity);
        Supplier<Boolean> response = script.next();
        onCall.accept(entity);
        response.get();
    }
}
