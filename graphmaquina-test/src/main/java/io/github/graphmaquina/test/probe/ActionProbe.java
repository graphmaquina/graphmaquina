package io.github.graphmaquina.test.probe;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.test.GraphAssertionError;
import io.github.graphmaquina.test.internal.ResponseScript;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A {@link TransitionAction} whose answers are written by the test instead of by a domain. Every invocation is
 * recorded in the probe's own {@link ProbeCalls} and in the shared {@link CallTrace}, then answered with the
 * next scripted response: the queue built by {@link #thenReturn} and {@link #thenThrow} in the order it was
 * built, and once that queue runs out the default set by {@link #otherwiseReturn}. A call the script no longer
 * covers raises a {@link GraphAssertionError} naming the probe and the ordinal of the call.
 *
 * <p>Configuration is meant to finish before the probe reaches a machine; after that, invoking it from several
 * threads is safe and each thread is served a different queued response. Recording a call, adding it to the
 * trace and withdrawing its response are one step, so the invocation at position p of {@link #calls()} is the
 * one that was served the response of call p + 1, and this probe's own entries in the trace keep the order of
 * its calls, however the threads interleave. The side effect registered with {@link #onCall} runs on the
 * calling thread, after the response has been withdrawn and before it is returned or thrown, so it also runs
 * for a call whose response is an exception, and does not run for a call the script no longer covers, which is
 * recorded and then fails.
 *
 * @param <E> the entity type of the machine the action belongs to
 * @param <S> the state enum of that machine
 * @param <T> the transition identifier enum of that machine
 */
public final class ActionProbe<E, S extends Enum<S>, T extends Enum<T>> implements TransitionAction<E, S, T> {

    private final String name;
    private final CallTrace trace;
    private final ProbeCalls<ActionCall<E, S, T>> calls;
    private final ResponseScript<ActionOutcome> script;
    private final Object entry = new Object();
    private volatile Consumer<TransitionContext<E, S, T>> onCall = context -> { };

    private ActionProbe(String name, CallTrace trace) {
        this.name = name;
        this.trace = trace;
        this.calls = new ProbeCalls<>(name);
        this.script = new ResponseScript<>(name);
    }

    /**
     * The trace reports {@code name} exactly as given and nothing checks it against the graph, so pass the name
     * the graph gives this action if the trace is to read like the graph. A null argument raises
     * {@link NullPointerException} and a blank name {@link IllegalArgumentException}.
     */
    public static <E, S extends Enum<S>, T extends Enum<T>> ActionProbe<E, S, T> create(String name,
            CallTrace trace) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(trace, "trace");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        return new ActionProbe<>(name, trace);
    }

    /** Queues one outcome, answered after every response queued before it. */
    public ActionProbe<E, S, T> thenReturn(ActionOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        script.then(() -> outcome);
        return this;
    }

    /** Queues one exception, thrown by identity: the call throws this very instance. */
    public ActionProbe<E, S, T> thenThrow(RuntimeException error) {
        Objects.requireNonNull(error, "error");
        script.then(() -> { throw error; });
        return this;
    }

    /** Sets the outcome answered by every call the queue no longer covers; a later call replaces it. */
    public ActionProbe<E, S, T> otherwiseReturn(ActionOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        script.otherwise(() -> outcome);
        return this;
    }

    /**
     * Replaces the side effect run on every call the script answers, a throwing response included but not a
     * call the script no longer covers. It receives the live context, which the engine invalidates when the
     * transaction ends, so read it now rather than storing it.
     */
    public ActionProbe<E, S, T> onCall(Consumer<TransitionContext<E, S, T>> onCall) {
        this.onCall = Objects.requireNonNull(onCall, "onCall");
        return this;
    }

    public ProbeCalls<ActionCall<E, S, T>> calls() {
        return calls;
    }

    @Override
    public ActionOutcome process(TransitionContext<E, S, T> context) {
        ActionCall<E, S, T> call = new ActionCall<>(context.entity(), context.from(), context.transition(),
                context.to(), context.depth(), context.input());
        Supplier<ActionOutcome> response;
        synchronized (entry) {
            calls.record(call);
            trace.record(name, call);
            response = script.next();
        }
        onCall.accept(context);
        return response.get();
    }
}
