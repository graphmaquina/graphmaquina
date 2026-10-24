package io.github.graphmaquina.test;

import io.github.graphmaquina.api.Reaction;
import io.github.graphmaquina.test.internal.Checks;
import lombok.NonNull;

/**
 * Chainable, typed assertion over a {@link Reaction.Aborted}, obtained from
 * {@link ReactionAssert#isAborted()}. The re-evaluation, or the lookup of the owner to re-evaluate, threw an
 * exception and the engine reported it here instead of propagating it, so there is no outcome to read and
 * the cause is the whole contract. The cause is only ever compared by type or by reference — no expectation
 * here reads its message — and a reevaluation's failure is its {@code CallFailedException}, and a checked exception
 * an owner lookup threw is compared as the {@link java.lang.reflect.UndeclaredThrowableException} that wraps it.
 *
 * @param <E> entity type of the machine the reaction was selected for
 * @param <S> state enum of the machine the reaction was selected for
 * @param <T> transition identifier enum of that machine
 */
public final class AbortedReactionAssert<E, S extends Enum<S>, T extends Enum<T>> {

    private final String context;
    private final Reaction.Aborted aborted;

    AbortedReactionAssert(String context, Reaction.Aborted aborted) {
        this.context = context;
        this.aborted = aborted;
    }

    /** Satisfied by any cause the type accepts, subclasses included. */
    public AbortedReactionAssert<E, S, T> hasCause(@NonNull Class<? extends Throwable> expected) {
        RuntimeException cause = aborted.cause();
        if (!expected.isInstance(cause)) {
            Checks.fail(context, "cause", expected, cause);
        }
        return this;
    }

    /** Compares by reference, so a distinct throwable of the same type does not satisfy it. */
    public AbortedReactionAssert<E, S, T> hasCauseSameAs(@NonNull Throwable expected) {
        Checks.same(context, "cause", expected, aborted.cause());
        return this;
    }
}
