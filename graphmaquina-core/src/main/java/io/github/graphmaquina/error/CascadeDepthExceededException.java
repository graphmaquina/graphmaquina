package io.github.graphmaquina.error;

import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.MachineCall;
import lombok.Getter;

/** Thrown when a cascade, or the exit of a sub-machine, goes deeper than the configured maximum. */
@Getter
public final class CascadeDepthExceededException extends TransitionAbortedException {

    private final int maxDepth;

    public CascadeDepthExceededException(MachineCall call, AbortDetails details, int maxDepth) {
        super(call, CallPhase.ACTION, details,
                "the cascade exceeds maxCascadeDepth = " + maxDepth);
        this.maxDepth = maxDepth;
    }
}
