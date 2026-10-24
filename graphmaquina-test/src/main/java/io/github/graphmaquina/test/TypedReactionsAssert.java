package io.github.graphmaquina.test;

import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Reaction;
import io.github.graphmaquina.test.internal.Checks;
import java.util.List;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/**
 * The reactions of one machine and one entity, in the order the engine dispatched them, obtained from
 * {@link ReactionsAssert#forEntity(MachineKey, Object)}. Selection has already happened, so the list is
 * never null here and a missing reaction is reported as a size or index expectation rather than as an
 * out-of-bounds crash. The key travels with the view because a reaction's outcome is published untyped and
 * is narrowed only when it is read.
 *
 * @param <E> entity type of the machine
 * @param <S> state enum of the machine
 * @param <T> transition identifier enum of the machine
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public final class TypedReactionsAssert<E, S extends Enum<S>, T extends Enum<T>> {

    private final String context;
    private final MachineKey<E, S, T> key;
    private final List<Reaction> actual;

    public TypedReactionsAssert<E, S, T> hasSize(int expected) {
        Checks.equal(context, "size", expected, actual.size());
        return this;
    }

    /** The one reaction of this entity; a selection of any other size fails rather than picking the first. */
    public ReactionAssert<E, S, T> single() {
        Checks.equal(context, "size", 1, actual.size());
        return atIndex(0);
    }

    public ReactionAssert<E, S, T> atIndex(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("index must not be negative: " + index);
        }
        Checks.equal(context, "index " + index + " present", Boolean.TRUE, index < actual.size());
        return new ReactionAssert<>(context + "[" + index + "]", key, actual.get(index));
    }
}
