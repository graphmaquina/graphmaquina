package io.github.graphmaquina.error;

import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.MachineCall;
import java.util.List;
import java.util.Objects;
import lombok.Getter;

/**
 * COLLECT policy of {@code cascadeAll}: the transition aborts of the elements that failed, in element order. Each
 * failed element is rolled back and the next one still runs; this exception is thrown once the last one has run, and
 * it poisons the transaction. Only a {@link TransitionAbortedException} is collected: any other failure poisons the
 * transaction through an abort that carries it and stops the cascade at once: the elements after it do not run.
 */
@Getter
public final class CascadeException extends TransitionAbortedException {

    private final List<TransitionAbortedException> errors;

    public CascadeException(MachineCall call, AbortDetails details, List<TransitionAbortedException> errors) {
        super(call, CallPhase.ACTION, details,
                Objects.requireNonNull(errors, "errors").size() + " cascade element(s) failed");
        this.errors = List.copyOf(errors);
    }
}
