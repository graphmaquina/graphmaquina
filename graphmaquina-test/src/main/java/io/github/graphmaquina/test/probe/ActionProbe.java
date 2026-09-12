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
 * threads is safe and each thread is served a different queued response. The side effect registered with
 * {@link #onCall} runs on the calling thread, after the response has been withdrawn and before it is returned
 * or thrown, so it also runs for a call whose response is an exception.
 *
 * @param <E> the entity type of the machine the action belongs to
 * @param <S> the state enum of that machine
 */
public final class ActionProbe<E, S extends Enum<S>> implements TransitionAction<E, S> {

    private final String name;
    private final CallTrace trace;
    private final ProbeCalls<ActionCall<E, S>> calls;
    private final ResponseScript<ActionOutcome> script;
    private volatile Consumer<TransitionContext<E, S>> onCall = context -> { };

    private ActionProbe(String name, CallTrace trace) {
        this.name = name;
        this.trace = trace;
        this.calls = new ProbeCalls<>(name);
        this.script = new ResponseScript<>(name);
    }

    /** The name must be the one the graph gives this action, since that is what the trace reports. */
    public static <E, S extends Enum<S>> ActionProbe<E, S> create(String name, CallTrace trace) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(trace, "trace");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        return new ActionProbe<>(name, trace);
    }

    /** Queues one outcome, answered after every response queued before it. */
    public ActionProbe<E, S> thenReturn(ActionOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        script.then(() -> outcome);
        return this;
    }

    /** Queues one exception, thrown by identity: the call throws this very instance. */
    public ActionProbe<E, S> thenThrow(RuntimeException error) {
        Objects.requireNonNull(error, "error");
        script.then(() -> { throw error; });
        return this;
    }

    /** Sets the outcome answered by every call the queue no longer covers; a later call replaces it. */
    public ActionProbe<E, S> otherwiseReturn(ActionOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        script.otherwise(() -> outcome);
        return this;
    }

    /**
     * Replaces the side effect run on every call. It receives the live context, which the engine invalidates
     * when the transaction ends, so read it now rather than storing it.
     */
    public ActionProbe<E, S> onCall(Consumer<TransitionContext<E, S>> onCall) {
        this.onCall = Objects.requireNonNull(onCall, "onCall");
        return this;
    }

    public ProbeCalls<ActionCall<E, S>> calls() {
        return calls;
    }

    @Override
    public ActionOutcome process(TransitionContext<E, S> context) {
        ActionCall<E, S> call =
                new ActionCall<>(context.entity(), context.from(), context.to(), context.depth());
        calls.record(call);
        trace.record(name, call);
        Supplier<ActionOutcome> response = script.next();
        onCall.accept(context);
        return response.get();
    }
}
