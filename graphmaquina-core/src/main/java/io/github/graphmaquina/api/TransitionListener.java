package io.github.graphmaquina.api;

/**
 * Observer of every outcome a registry produces. Invoked once per {@code execute} and once per
 * {@code reevaluate}, whether called directly or triggered by a watch reaction, after the reactions of that
 * call. Nested reaction outcomes arrive in their own call, so implementations never walk {@code reactions()}.
 * A listener that throws surfaces {@code AfterCommitException} to the caller of that call.
 */
@FunctionalInterface
public interface TransitionListener {

    void onOutcome(MachineKey<?, ?, ?> key, Outcome<?, ?, ?> outcome);
}
