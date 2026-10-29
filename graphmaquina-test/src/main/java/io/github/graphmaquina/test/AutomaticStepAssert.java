package io.github.graphmaquina.test;

import io.github.graphmaquina.api.AutomaticStep;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.test.internal.Checks;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/**
 * Chainable, typed assertion over a single {@link AutomaticStep}, obtained from
 * {@link TypedAutomaticStepsAssert#single()} or {@link TypedAutomaticStepsAssert#atIndex(int)}. A step either carries
 * the outcome of an attempt that ran to completion, or is aborted, because the attempt or the reads the chain makes
 * around its attempts threw, and carries the cause instead; the two narrowing methods assert which one it was and hand
 * back the view that exposes what only that one carries.
 *
 * @param <E> entity type of the machine
 * @param <S> state enum of the machine
 * @param <T> transition identifier enum of the machine
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public final class AutomaticStepAssert<E, S extends Enum<S>, T extends Enum<T>> {

    private final String context;
    private final MachineKey<E, S, T> key;
    private final AutomaticStep actual;

    public DoneAutomaticStepAssert<E, S, T> isDone() {
        return new DoneAutomaticStepAssert<>(context, key, (AutomaticStep.Done) narrow("Done"));
    }

    public AbortedAutomaticStepAssert<E, S, T> isAborted() {
        return new AbortedAutomaticStepAssert<>(context, (AutomaticStep.Aborted) narrow("Aborted"));
    }

    private AutomaticStep narrow(String kind) {
        Checks.equal(context, "kind", kind, actual.getClass().getSimpleName());
        return actual;
    }
}
