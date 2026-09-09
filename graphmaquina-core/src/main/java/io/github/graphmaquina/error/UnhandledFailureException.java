package io.github.graphmaquina.error;

import java.util.Objects;
import lombok.Getter;

/** Domain failure without a declared onFailure. */
@Getter
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
}
