package io.github.graphmaquina.test;

import io.github.graphmaquina.api.AutomaticStep;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.test.internal.CheckedTypes;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/**
 * Chainable, typed assertion over an {@link AutomaticStep.Done}, obtained from {@link AutomaticStepAssert#isDone()}.
 * The attempt ran, so everything it says is in its outcome: an applied or a failed one when it ran an edge, and an
 * ignored one when its state had no automatic edge left or none of its candidates was admitted.
 *
 * @param <E> entity type of the machine
 * @param <S> state enum of the machine
 * @param <T> transition identifier enum of the machine
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public final class DoneAutomaticStepAssert<E, S extends Enum<S>, T extends Enum<T>> {

    private final String context;
    private final MachineKey<E, S, T> key;
    private final AutomaticStep.Done done;

    /**
     * The outcome of the attempt, narrowed to the machine the selection named. A step publishes its outcome untyped, so
     * an outcome that does not belong to that machine is a programming error in the calling test and raises
     * {@link IllegalArgumentException} rather than being cast blindly.
     */
    public OutcomeAssert<E, S, T> outcome() {
        return new OutcomeAssert<>(context + ".outcome", CheckedTypes.outcome(key, done.outcome()));
    }
}
