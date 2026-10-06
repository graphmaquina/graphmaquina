package io.github.graphmaquina.error;

import lombok.Getter;

/**
 * A chain of watch reactions grew longer than the registry's {@code maxWatchChain}. The reaction one step past the
 * limit evaluates no watch rule and ends as a {@link io.github.graphmaquina.api.Reaction.Aborted} carrying this
 * exception, so it reaches the application only there: neither execute nor reevaluate throws it to its caller.
 */
@Getter
public final class WatchChainExceededException extends TransitionAbortedException {

    private final int maxChain;

    public WatchChainExceededException(AbortDetails details, int maxChain) {
        super(details, "the chain of watch reactions exceeds maxWatchChain = " + maxChain);
        this.maxChain = maxChain;
    }
}
