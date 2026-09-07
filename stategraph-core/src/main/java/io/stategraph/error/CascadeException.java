package io.stategraph.error;

import java.util.List;
import java.util.Objects;
import lombok.Getter;

/** COLLECT policy: all the errors from a cascadeAll. */
@Getter
public final class CascadeException extends TransitionAbortedException {

    private final List<TransitionAbortedException> errors;

    public CascadeException(AbortDetails details, List<TransitionAbortedException> errors) {
        super(details, Objects.requireNonNull(errors, "errors").size() + " cascade element(s) failed");
        this.errors = List.copyOf(errors);
    }
}
