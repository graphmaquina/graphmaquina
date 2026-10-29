package io.github.graphmaquina.api;

import lombok.NonNull;

/**
 * Result of one automatic attempt that a commit triggered: an {@code advance} of an entity the commit moved, made as a
 * call of its own once the commit's watch reactions ran. A throwable that is not an Error, thrown by the attempt or by
 * the chain's own reads before it, never propagates, because the commit that triggered the attempt already happened:
 * it becomes {@link Aborted}, and the chain of that entity stops there. An Error is not caught: it propagates
 * unchanged to the caller whose commit triggered the attempt.
 */
public sealed interface AutomaticStep permits AutomaticStep.Done, AutomaticStep.Aborted {

    MachineKey<?, ?, ?> key();

    Object entity();

    /**
     * An attempt that ran to completion, carrying its outcome: {@link Outcome.Applied} or {@link Outcome.Failed} when
     * it ran an edge, and {@link Outcome.Ignored} when its state had no automatic edge left or none of its candidates
     * was admitted, whose {@code rejections()} then say why. The attempt is a call of its own, so the outcome's
     * {@link Outcome#flow()} is the attempt's flow, not the flow of the call that triggered it, and its reactions and
     * automatic steps are those of the attempt's own commit.
     */
    record Done(@NonNull MachineKey<?, ?, ?> key, @NonNull Object entity,
                @NonNull Outcome<?, ?, ?> outcome) implements AutomaticStep {
    }

    /**
     * An attempt that threw, or a chain that could not make its next attempt, naming the entity of the chain.
     * {@code cause} is RuntimeException, not StateMachineException, so that api does not depend on error. For an
     * attempt that threw, it is the attempt's {@code CallFailedException}, whose {@code statesWritten()} says how far
     * the attempt got in writing states: {@code NONE} when it wrote nothing, as an abort before its commit, the
     * {@code AutomaticChainExceededException} of an attempt past {@code maxAutomaticChain} included; {@code PARTIAL}
     * when one of its setters threw; and {@code ALL} when one of its hooks threw. The flow it hands out is the
     * attempt's own. Before each attempt, outside any call, the chain reads the automatic edges of the entity's state
     * and compares the entity with those that calls nested in the post-commit moved, which runs the automaticFrom of
     * a {@link GraphDefinition} written by hand and the hashCode and equals of the identities it compares: what those
     * throw is the cause as it is, a checked exception or any other throwable that is neither a RuntimeException nor
     * an Error wrapped in {@link java.lang.reflect.UndeclaredThrowableException}, and an InterruptedException it
     * wraps interrupts the thread again.
     */
    record Aborted(@NonNull MachineKey<?, ?, ?> key, @NonNull Object entity,
                   @NonNull RuntimeException cause) implements AutomaticStep {
    }
}
