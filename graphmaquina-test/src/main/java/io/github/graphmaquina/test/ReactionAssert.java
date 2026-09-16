package io.github.graphmaquina.test;

import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Reaction;
import io.github.graphmaquina.test.internal.Checks;

/**
 * Chainable, typed assertion over a single {@link Reaction}, obtained from
 * {@link TypedReactionsAssert#single()} or {@link TypedReactionsAssert#atIndex(int)}. A watch re-evaluation
 * either ran to completion and carries its own outcome, or aborted and carries the cause instead; the two
 * narrowing methods assert which one it was and hand back the view that exposes what only that one carries.
 *
 * @param <E> entity type of the machine
 * @param <S> state enum of the machine
 * @param <T> transition identifier enum of the machine
 */
public final class ReactionAssert<E, S extends Enum<S>, T extends Enum<T>> {

    private final String context;
    private final MachineKey<E, S, T> key;
    private final Reaction actual;

    ReactionAssert(String context, MachineKey<E, S, T> key, Reaction actual) {
        this.context = context;
        this.key = key;
        this.actual = actual;
    }

    public DoneReactionAssert<E, S, T> isDone() {
        return new DoneReactionAssert<>(key, (Reaction.Done) narrow("Done"));
    }

    public AbortedReactionAssert<E, S, T> isAborted() {
        return new AbortedReactionAssert<>(context, (Reaction.Aborted) narrow("Aborted"));
    }

    private Reaction narrow(String kind) {
        Checks.equal(context, "kind", kind, actual.getClass().getSimpleName());
        return actual;
    }
}
