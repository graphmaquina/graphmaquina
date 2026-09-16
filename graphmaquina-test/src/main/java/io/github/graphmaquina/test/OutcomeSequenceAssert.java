package io.github.graphmaquina.test;

import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.test.internal.Checks;
import java.util.List;

/**
 * The outcomes of one machine and one entity, in the order the recorder observed them, obtained from
 * {@link RecordedTransitionsAssert#forEntity(MachineKey, Object)}. Selection has already happened, so
 * the list is never null here and a missing callback is reported as a size or index expectation rather
 * than as an out-of-bounds crash.
 *
 * @param <E> entity type of the machine
 * @param <S> state enum of the machine
 * @param <T> transition identifier enum of the machine
 */
public final class OutcomeSequenceAssert<E, S extends Enum<S>, T extends Enum<T>> {

    private final String context;
    private final List<Outcome<E, S, T>> actual;

    OutcomeSequenceAssert(String context, List<Outcome<E, S, T>> actual) {
        this.context = context;
        this.actual = actual;
    }

    public OutcomeSequenceAssert<E, S, T> hasSize(int expected) {
        Checks.equal(context, "size", expected, actual.size());
        return this;
    }

    /** The one callback about this entity; a selection of any other size fails rather than picking the first. */
    public OutcomeAssert<E, S, T> single() {
        Checks.equal(context, "size", 1, actual.size());
        return atIndex(0);
    }

    public OutcomeAssert<E, S, T> atIndex(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("index must not be negative: " + index);
        }
        Checks.equal(context, "index " + index + " present", Boolean.TRUE, index < actual.size());
        return new OutcomeAssert<>(context + "[" + index + "]", actual.get(index));
    }
}
