package io.github.graphmaquina.error;

import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.MachineCall;
import lombok.Getter;

/**
 * A chain of automatic attempts grew longer than the registry's {@code maxAutomaticChain}. The attempt one past the
 * limit still selects its candidate: when it admits one, it aborts once that candidate's guards passed, before its
 * action runs and without writing anything, with this exception, in GUARD when the candidate has guards and in
 * RESOLVE otherwise; its details name that candidate's edge. It ends as an
 * {@link io.github.graphmaquina.api.AutomaticStep.Aborted} carrying this exception, so it reaches the application only
 * there, and in the {@code onFailed} of the attempt's call that the registry's observers receive: neither execute, nor
 * reevaluate, nor advance throws it to its caller.
 */
@Getter
public final class AutomaticChainExceededException extends TransitionAbortedException {

    private final int maxChain;

    public AutomaticChainExceededException(MachineCall call, CallPhase phase, AbortDetails details, int maxChain) {
        super(call, phase, details, "the chain of automatic attempts exceeds maxAutomaticChain = " + maxChain);
        this.maxChain = maxChain;
    }
}
