package io.github.graphmaquina.error;

import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.MachineCall;
import lombok.Getter;

/**
 * A chain of watch reactions grew longer than the registry's {@code maxWatchChain}. The reaction one step past the
 * limit evaluates no watch rule and ends as a {@link io.github.graphmaquina.api.Reaction.Aborted} carrying this
 * exception, so it reaches the application only there, and in the {@code onFailed} of the reaction's call that the
 * registry's observers receive: neither execute, nor reevaluate, nor advance throws it to its caller.
 */
@Getter
public final class WatchChainExceededException extends TransitionAbortedException {

    private final int maxChain;

    public WatchChainExceededException(MachineCall call, AbortDetails details, int maxChain) {
        super(call, CallPhase.RESOLVE, details,
                "the chain of watch reactions exceeds maxWatchChain = " + maxChain);
        this.maxChain = maxChain;
    }
}
