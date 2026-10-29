package io.github.graphmaquina.test;

import io.github.graphmaquina.api.AutomaticStep;
import io.github.graphmaquina.test.internal.Checks;
import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Chainable, typed assertion over an {@link AutomaticStep.Aborted}, obtained from
 * {@link AutomaticStepAssert#isAborted()} or from {@link BaseOutcomeAssert#automaticAbort()}. The attempt, or a read
 * the chain makes around its attempts, threw an exception and the engine reported it here instead of propagating it,
 * so there is no outcome to read and the cause is the whole contract. The cause is only ever compared by type or by
 * reference: no expectation here reads its message. An attempt that ran reports its {@code CallFailedException}, and a
 * checked exception thrown by a read around the attempts is compared as the
 * {@link java.lang.reflect.UndeclaredThrowableException} that wraps it.
 *
 * @param <E> entity type of the machine the step was selected for
 * @param <S> state enum of the machine the step was selected for
 * @param <T> transition identifier enum of that machine
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public final class AbortedAutomaticStepAssert<E, S extends Enum<S>, T extends Enum<T>> {

    private final String context;
    private final AutomaticStep.Aborted aborted;

    /** Satisfied by any cause the type accepts, subclasses included. */
    public AbortedAutomaticStepAssert<E, S, T> hasCause(@NonNull Class<? extends Throwable> expected) {
        RuntimeException cause = aborted.cause();
        if (!expected.isInstance(cause)) {
            Checks.fail(context, "cause", expected, cause);
        }
        return this;
    }

    /** Compares by reference, so a distinct throwable of the same type does not satisfy it. */
    public AbortedAutomaticStepAssert<E, S, T> hasCauseSameAs(@NonNull Throwable expected) {
        Checks.same(context, "cause", expected, aborted.cause());
        return this;
    }
}
