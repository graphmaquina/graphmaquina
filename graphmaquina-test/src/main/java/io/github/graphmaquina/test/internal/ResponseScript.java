package io.github.graphmaquina.test.internal;

import io.github.graphmaquina.test.GraphAssertionError;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The queue of responses a probe hands out, one per call, with an optional default once the queue runs dry.
 * A response is a {@link Supplier} so that returning a value and throwing an exception are the same kind of
 * entry: {@link #next()} only withdraws it, and the probe invokes it afterwards, outside this object's lock,
 * so a response that blocks or throws never holds up another thread's withdrawal. Withdrawal is atomic, so
 * two threads calling the same probe are served different entries and the call ordinals they are numbered
 * with never repeat. Configuration is expected to finish before the probe is used.
 *
 * @param <R> the value a scripted response returns
 */
@RequiredArgsConstructor
public final class ResponseScript<R> {

    @NonNull
    private final String probeName;
    private final Deque<Supplier<R>> queue = new ArrayDeque<>();
    private Supplier<R> fallback;
    private int served;

    /** Appends one response, served after every response queued before it. */
    public void then(@NonNull Supplier<R> response) {
        synchronized (this) {
            queue.addLast(response);
        }
    }

    /** Sets the response served by every call the queue no longer covers; a later call replaces it. */
    public void otherwise(@NonNull Supplier<R> response) {
        synchronized (this) {
            fallback = response;
        }
    }

    /**
     * Withdraws the response for the calling invocation. Fails with a {@link GraphAssertionError} naming the
     * probe and the ordinal of the call — the first call is 1 — when the queue is empty and no default was set.
     */
    public Supplier<R> next() {
        synchronized (this) {
            int ordinal = ++served;
            Supplier<R> queued = queue.pollFirst();
            if (queued != null) {
                return queued;
            }
            if (fallback != null) {
                return fallback;
            }
            throw new GraphAssertionError("probe " + probeName, "scripted response for call " + ordinal,
                    "a scripted response or a default", "no response left");
        }
    }
}
