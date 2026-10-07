package io.github.graphmaquina.error;

import io.github.graphmaquina.api.ActionRecord;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.MachineCall;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.StatesWritten;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.Getter;

/**
 * ABORT: nothing moved; the unit of work, if one was started, was discarded. Its phase is RESOLVE, GUARD or ACTION,
 * and it never wrote a state.
 *
 * <p>When a guard or an action failed, the cause is what it threw, checked exceptions included, once unwrapped from
 * CompletionException and ExecutionException; a {@link StateMachineException} found there never becomes a cause,
 * since the engine rethrows it as is. It propagates unchanged even when it comes from another root, such as the
 * failure of another machine's call that an action joined: an exception of this type thrown that way carries details
 * that describe that root, not the call that received it. An {@code Error} is never routed: thrown as itself by a
 * callback on the calling thread, it propagates unchanged rather than as this exception. It becomes the cause of one
 * only when it did not reach the engine as itself: when it completed the stage of an asynchronous action or guard,
 * when a guard or an action threw it wrapped in CompletionException or ExecutionException, or when an action
 * swallowed it from a cascade and the poison it left aborted the root.
 */
public class TransitionAbortedException extends CallFailedException {

    @Getter
    private final AbortDetails details;
    private final CallPhase phase;

    public TransitionAbortedException(MachineCall call, CallPhase phase, AbortDetails details, String message) {
        super(call, describe(details, message));
        this.phase = beforeTheCommit(phase);
        this.details = details;
    }

    public TransitionAbortedException(MachineCall call, CallPhase phase, AbortDetails details, String message,
                                      Throwable cause) {
        super(call, describe(details, message), cause);
        this.phase = beforeTheCommit(phase);
        this.details = details;
    }

    /** RESOLVE, GUARD or ACTION: an abort always happens before the commit. */
    @Override
    public CallPhase phase() { return phase; }

    /** NONE: an abort discards the unit of work before any setter runs. */
    @Override
    public StatesWritten statesWritten() { return StatesWritten.NONE; }

    /** Actions that ran, in order, with their status. The only record of which external effects were left loose. */
    @Override
    public List<ActionRecord> journal() { return details.journal(); }

    public MachineKey<?, ?, ?> key() { return details.key(); }

    public Object entity() { return details.entity(); }

    public Enum<?> from() { return details.from(); }

    /** The requested identifier, absent when the abort happened before one was read. */
    public Optional<Enum<?>> transition() { return details.transition(); }

    /** The destination of the resolved edge; absent when no edge was selected. */
    public Optional<Enum<?>> to() { return details.to(); }

    public Optional<String> action() { return details.action(); }

    public List<String> cascadePath() { return details.cascadePath(); }

    private static CallPhase beforeTheCommit(CallPhase phase) {
        Objects.requireNonNull(phase, "phase");
        if (phase != CallPhase.RESOLVE && phase != CallPhase.GUARD && phase != CallPhase.ACTION) {
            throw new IllegalArgumentException("an abort happens before the commit, not in " + phase);
        }
        return phase;
    }

    private static String describe(AbortDetails details, String message) {
        Objects.requireNonNull(details, "details");
        Objects.requireNonNull(message, "message");
        String path = details.cascadePath().isEmpty() ? "" : " [route " + String.join(" → ", details.cascadePath()) + "]";
        return "transition aborted in " + details.key() + " " + details.describeEdge() + ": " + message + path;
    }
}
