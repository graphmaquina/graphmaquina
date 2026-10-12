package io.github.graphmaquina.error;

import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.MachineCall;
import java.util.List;
import java.util.Objects;
import lombok.Getter;

/**
 * COLLECT policy of {@code cascadeAll}: the transition aborts of the elements that failed, in element order. Each
 * failed element is rolled back and the next one still runs; this exception is thrown once the last one has run, and
 * it poisons the transaction. Only a transition abort of the call is collected, and not one whose element's
 * sub-machine binding check could not read a parent or a child, such as a parent whose state is null, nor one whose
 * element a nested cascade poisoned with a failure that is no failure of the call, such as an unknown machine or a
 * peer's null state, even when the element's action caught it. Any other failure poisons the transaction, through an
 * abort that carries it when it is no failure of the call, and stops the cascade at once: the elements after it do not
 * run. So does what an identity's {@code equals} or {@code hashCode} throws while a failed element is rolled back: the
 * abort that carries it poisons the transaction in place of that element's abort, which it holds as suppressed.
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
