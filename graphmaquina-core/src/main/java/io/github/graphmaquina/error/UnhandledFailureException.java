package io.github.graphmaquina.error;

import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.MachineCall;
import java.util.Objects;
import lombok.Getter;

/**
 * Domain failure without a declared onFailure: the action returned a failure, or threw a throwable that a type its
 * edge declares accepts, and no route takes it. The engine rolls the step back before it finds that no route takes the
 * failure, so {@link #flow()} lacks the writes of that step and of the steps it cascaded to, and their hops and starts
 * in its trail.
 */
@Getter
public final class UnhandledFailureException extends TransitionAbortedException {

    private final String reason;

    public UnhandledFailureException(MachineCall call, AbortDetails details, String reason) {
        super(call, CallPhase.ACTION, details,
                "domain failure without onFailure: " + Objects.requireNonNull(reason, "reason"));
        this.reason = reason;
    }

    public UnhandledFailureException(MachineCall call, AbortDetails details, String reason, Throwable cause) {
        super(call, CallPhase.ACTION, details,
                "domain failure without onFailure: " + Objects.requireNonNull(reason, "reason"), cause);
        this.reason = reason;
    }
}
