package io.github.graphmaquina.error;

import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.MachineCall;
import java.util.Objects;
import lombok.Getter;

/** Domain failure without a declared onFailure. */
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
