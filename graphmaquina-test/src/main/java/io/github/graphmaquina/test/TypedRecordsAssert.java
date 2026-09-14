package io.github.graphmaquina.test;

import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.TransitionRecord;
import io.github.graphmaquina.test.internal.Checks;
import java.util.List;

/**
 * The records of one machine and one entity, in the order the transaction produced them, obtained from
 * {@link TransitionRecordsAssert#forEntity(MachineKey, Object)}. Selection has already happened, so the
 * list is never null here and a missing record is reported as a size or index expectation rather than as
 * an out-of-bounds crash.
 *
 * @param <E> entity type of the machine
 * @param <S> state enum of the machine
 */
public final class TypedRecordsAssert<E, S extends Enum<S>> {

    private final String context;
    private final List<TransitionRecord<E, S>> actual;

    TypedRecordsAssert(String context, List<TransitionRecord<E, S>> actual) {
        this.context = context;
        this.actual = actual;
    }

    public TypedRecordsAssert<E, S> hasSize(int expected) {
        Checks.equal(context, "size", expected, actual.size());
        return this;
    }

    /** The one record of this entity; a selection of any other size fails rather than picking the first. */
    public TransitionRecordAssert<E, S> single() {
        Checks.equal(context, "size", 1, actual.size());
        return atIndex(0);
    }

    public TransitionRecordAssert<E, S> atIndex(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("index must not be negative: " + index);
        }
        Checks.equal(context, "index " + index + " present", Boolean.TRUE, index < actual.size());
        return new TransitionRecordAssert<>(context + "[" + index + "]", actual.get(index));
    }
}
