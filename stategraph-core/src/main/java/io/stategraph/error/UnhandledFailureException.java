package io.stategraph.error;

import java.util.Objects;

/** Domain failure without a declared onFailure. */
public final class UnhandledFailureException extends TransitionAbortedException {

    private final String reason;

    public UnhandledFailureException(AbortDetails details, String reason) {
        super(details, "domain failure without onFailure: " + Objects.requireNonNull(reason, "reason"));
        this.reason = reason;
    }

    public UnhandledFailureException(AbortDetails details, String reason, Throwable cause) {
        super(details, "domain failure without onFailure: " + Objects.requireNonNull(reason, "reason"), cause);
        this.reason = reason;
    }

    public String reason() { return reason; }
}
