package io.github.graphmaquina.error;

import java.util.List;
import java.util.Objects;
import lombok.Getter;

/**
 * COLLECT policy of {@code cascadeAll}: the transition aborts of the elements that failed, in element order. Each
 * failed element is rolled back and the next one still runs; this exception is thrown once the last one has run, and
 * it poisons the transaction. Only a {@link TransitionAbortedException} is collected: any other failure poisons the
 * transaction and propagates at once, and the elements after it do not run.
 */
@Getter
public final class CascadeException extends TransitionAbortedException {

    private final List<TransitionAbortedException> errors;

    public CascadeException(AbortDetails details, List<TransitionAbortedException> errors) {
        super(details, Objects.requireNonNull(errors, "errors").size() + " cascade element(s) failed");
        this.errors = List.copyOf(errors);
    }
}
