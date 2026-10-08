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
 * ABORT: nothing moved. When this exception ends the call, the unit of work, if one was started, was discarded; the
 * abort of a nested step first reaches the action that cascaded it, or is collected under COLLECT, as the last
 * paragraph says. Its phase is RESOLVE, GUARD or ACTION, and it never wrote a state.
 *
 * <p>When a guard or an action failed, the cause is what it threw, checked exceptions and library exceptions of other
 * calls included, once unwrapped from CompletionException and ExecutionException. A failure of this same call that a
 * guard or an action lets through, such as the abort a failed cascade threw into the action, is never wrapped: the
 * engine throws it as itself or, when the transaction is poisoned, the failure that poisoned the transaction. An
 * {@code Error} is never routed: thrown as itself by a callback on the calling thread, it propagates unchanged rather
 * than as this exception. It becomes the cause of one only when it did not reach the engine as itself: when it
 * completed the stage of an asynchronous action or guard, when a guard or an action threw it wrapped in
 * CompletionException or ExecutionException, or when an action swallowed it from a cascade and the poison it left
 * aborted the root.
 *
 * <p>Where this library's documentation says that a step aborts, that something aborts the call, that the call aborts,
 * or that the call ends in this exception or one of its subclasses, the engine throws this exception, or a subclass,
 * from the step that is running, or from the call itself when it fails before any step runs. At the root step, the call
 * throws it. Outside COLLECT, the abort of a nested step, one that a cascade runs, poisons the transaction unless it is
 * already poisoned, and reaches the action that cascaded it, which may catch it; under COLLECT, the cascade collects it
 * instead, unless the poison wraps a failure that is no failure of the call. What the caller finally receives therefore
 * depends on the enclosing steps.
 */
public class TransitionAbortedException extends CallFailedException {

    @Getter
    private final AbortDetails details;
    private final CallPhase phase;

    /**
     * An abort of {@code call} in {@code phase}, described by {@code details}.
     *
     * @throws IllegalArgumentException if {@code phase} is COMMIT, HOOK or REACTIONS: an abort happens before the
     *     commit
     */
    public TransitionAbortedException(MachineCall call, CallPhase phase, AbortDetails details, String message) {
        super(call, describe(details, message));
        this.phase = beforeTheCommit(phase);
        this.details = details;
    }

    /**
     * An abort of {@code call} in {@code phase}, described by {@code details}, caused by {@code cause}.
     *
     * @throws IllegalArgumentException if {@code phase} is COMMIT, HOOK or REACTIONS: an abort happens before the
     *     commit
     */
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

    /** The state the entity was in; absent when the abort happened before it was read. */
    public Optional<Enum<?>> from() { return details.from(); }

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
        String path = details.cascadePath().isEmpty()
                ? ""
                : " [route " + String.join(" → ", details.cascadePath()) + "]";
        return "transition aborted in " + details.key() + " " + details.describeEdge() + ": " + message + path;
    }
}
