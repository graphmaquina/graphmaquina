package io.stategraph.error;

import lombok.Getter;

/** Thrown when a chain of watch reactions goes longer than the configured maximum. */
@Getter
public final class WatchChainExceededException extends TransitionAbortedException {

    private final int maxChain;

    public WatchChainExceededException(AbortDetails details, int maxChain) {
        super(details, "the chain of watch reactions exceeds maxWatchChain = " + maxChain);
        this.maxChain = maxChain;
    }
}
