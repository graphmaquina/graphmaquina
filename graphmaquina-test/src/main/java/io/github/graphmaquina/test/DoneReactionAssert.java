package io.github.graphmaquina.test;

import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Reaction;
import io.github.graphmaquina.test.internal.CheckedTypes;

/**
 * Chainable, typed assertion over a {@link Reaction.Done}, obtained from {@link ReactionAssert#isDone()}.
 * The re-evaluation ran, so everything it says is in its outcome — including that the re-evaluation moved
 * nothing, which is an ignored outcome rather than an absent one.
 *
 * @param <E> entity type of the machine
 * @param <S> state enum of the machine
 * @param <T> transition identifier enum of the machine
 */
public final class DoneReactionAssert<E, S extends Enum<S>, T extends Enum<T>> {

    private final MachineKey<E, S, T> key;
    private final Reaction.Done done;

    DoneReactionAssert(MachineKey<E, S, T> key, Reaction.Done done) {
        this.key = key;
        this.done = done;
    }

    /**
     * The outcome of the re-evaluation, narrowed to the machine the selection named. A reaction publishes
     * its outcome untyped, so an outcome that does not belong to that machine is a programming error in the
     * calling test and raises {@link IllegalArgumentException} rather than being cast blindly.
     */
    public OutcomeAssert<E, S, T> outcome() {
        return OutcomeAssert.assertThat(CheckedTypes.outcome(key, done.outcome()));
    }
}
