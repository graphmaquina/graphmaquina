package io.stategraph.error;

import lombok.Getter;

/** Thrown when a cascade goes deeper than the configured maximum. */
@Getter
public final class CascadeDepthExceededException extends TransitionAbortedException {

    private final int maxDepth;

    public CascadeDepthExceededException(AbortDetails details, int maxDepth) {
        super(details, "the cascade exceeds maxCascadeDepth = " + maxDepth);
        this.maxDepth = maxDepth;
    }
}
