package io.github.graphmaquina.test.probe;

import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.TransitionGuard;
import io.github.graphmaquina.test.GraphAssertionError;
import io.github.graphmaquina.test.internal.ResponseScript;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A {@link TransitionGuard} whose verdicts are written by the test instead of by a domain. It behaves exactly
 * as {@link ActionProbe} does, with {@link GuardResult} in place of the action outcome: every invocation is
 * recorded in the probe's own {@link ProbeCalls} and in the shared {@link CallTrace}, answered from the queue
 * built by {@link #thenReturn} and {@link #thenThrow} and then from the default set by
 * {@link #otherwiseReturn}, and a call the script no longer covers raises a {@link GraphAssertionError} naming
 * the probe and the ordinal of the call.
 *
 * <p>The engine evaluates the guards of an edge in declaration order and stops at the first rejection, so a
 * probe placed after a rejecting guard records nothing at all.
 *
 * @param <E> the entity type of the machine the guard belongs to
 * @param <S> the state enum of that machine
 * @param <T> the transition identifier enum of that machine
 */
public final class GuardProbe<E, S extends Enum<S>, T extends Enum<T>> implements TransitionGuard<E, S, T> {

    private final String name;
    private final CallTrace trace;
    private final ProbeCalls<GuardCall<E, S, T>> calls;
    private final ResponseScript<GuardResult> script;
    private final Object entry = new Object();
    private volatile Consumer<GuardContext<E, S, T>> onCall = context -> { };

    private GuardProbe(String name, CallTrace trace) {
        this.name = name;
        this.trace = trace;
        this.calls = new ProbeCalls<>(name);
        this.script = new ResponseScript<>(name);
    }

    /** The name must be the one the graph gives this guard, since that is what the trace reports. */
    public static <E, S extends Enum<S>, T extends Enum<T>> GuardProbe<E, S, T> create(String name,
            CallTrace trace) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(trace, "trace");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        return new GuardProbe<>(name, trace);
    }

    /** Queues one verdict, answered after every response queued before it. */
    public GuardProbe<E, S, T> thenReturn(GuardResult result) {
        Objects.requireNonNull(result, "result");
        script.then(() -> result);
        return this;
    }

    /** Queues one exception, thrown by identity: the call throws this very instance. */
    public GuardProbe<E, S, T> thenThrow(RuntimeException error) {
        Objects.requireNonNull(error, "error");
        script.then(() -> { throw error; });
        return this;
    }

    /** Sets the verdict answered by every call the queue no longer covers; a later call replaces it. */
    public GuardProbe<E, S, T> otherwiseReturn(GuardResult result) {
        Objects.requireNonNull(result, "result");
        script.otherwise(() -> result);
        return this;
    }

    /**
     * Replaces the side effect run on every call. It receives the live context, which the engine invalidates
     * when the transaction ends, so read it now rather than storing it.
     */
    public GuardProbe<E, S, T> onCall(Consumer<GuardContext<E, S, T>> onCall) {
        this.onCall = Objects.requireNonNull(onCall, "onCall");
        return this;
    }

    public ProbeCalls<GuardCall<E, S, T>> calls() {
        return calls;
    }

    @Override
    public GuardResult test(GuardContext<E, S, T> context) {
        GuardCall<E, S, T> call = new GuardCall<>(context.entity(), context.from(), context.transition(),
                context.to(), context.input());
        Supplier<GuardResult> response;
        synchronized (entry) {
            calls.record(call);
            trace.record(name, call);
            response = script.next();
        }
        onCall.accept(context);
        return response.get();
    }
}
