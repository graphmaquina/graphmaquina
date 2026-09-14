package io.github.graphmaquina.test.probe;

import java.util.Objects;

/**
 * One entry into a probe's callback, as the shared {@link CallTrace} saw it. The trace records the moment the
 * callback was entered and nothing after it, so an event says the probe was called — not that its response
 * succeeded, not that the transition committed, and not that a hook downstream of it ever ran.
 *
 * @param sequence position of this entry in the order the trace observed, starting at zero
 * @param name the probe's name, as it was given to the probe's factory
 * @param call what the probe recorded for this invocation: an {@link ActionCall}, a {@link GuardCall} or,
 *        for a hook, the entity it was handed
 */
public record TraceEvent(long sequence, String name, Object call) {

    public TraceEvent {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(call, "call");
    }
}
