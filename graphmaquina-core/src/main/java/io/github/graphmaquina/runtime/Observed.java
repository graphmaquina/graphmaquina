package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.CallFailure;
import io.github.graphmaquina.api.MachineCall;
import io.github.graphmaquina.api.Outcome;
import java.time.Duration;

/**
 * A call that its registry's observers are watching: the observers, the call, and the clock read just before they
 * were told it started, which the end event measures its elapsed time from.
 */
record Observed(Observers observers, MachineCall call, long startNanos) {

    void completed(Outcome<?, ?, ?> outcome) {
        observers.completed(call, outcome, elapsed());
    }

    void failed(CallFailure failure) {
        observers.failed(call, failure, elapsed());
    }

    private Duration elapsed() {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }
}
