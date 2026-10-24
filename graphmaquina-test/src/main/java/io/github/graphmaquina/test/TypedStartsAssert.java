package io.github.graphmaquina.test;

import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.SubmachineStart;
import io.github.graphmaquina.test.internal.Checks;
import java.util.List;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/**
 * The starts of one child of one machine, in the order the call logged them, obtained from
 * {@link StartsAssert#forChild(MachineKey, Object)}. A child starts once each time its parent enters the bound state,
 * so it may start more than once in one call. Selection has already happened, so a missing start is reported as a size
 * or index expectation rather than as an out-of-bounds crash.
 *
 * @param <P> entity type of the child machine
 * @param <Q> state enum of the child machine
 * @param <U> transition identifier enum of the child machine
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public final class TypedStartsAssert<P, Q extends Enum<Q>, U extends Enum<U>> {

    private final String context;
    private final List<SubmachineStart<?, ?, ?>> actual;

    public TypedStartsAssert<P, Q, U> hasSize(int expected) {
        Checks.equal(context, "size", expected, actual.size());
        return this;
    }

    /** The one start of this child; a selection of any other size fails rather than picking the first. */
    public StartAssert<P, Q, U> single() {
        Checks.equal(context, "size", 1, actual.size());
        return atIndex(0);
    }

    public StartAssert<P, Q, U> atIndex(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("index must not be negative: " + index);
        }
        Checks.equal(context, "index " + index + " present", Boolean.TRUE, index < actual.size());
        return new StartAssert<>(context + "[" + index + "]", actual.get(index));
    }
}
