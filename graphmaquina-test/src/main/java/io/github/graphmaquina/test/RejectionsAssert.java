package io.github.graphmaquina.test;

import io.github.graphmaquina.api.Eligibility;
import io.github.graphmaquina.test.internal.Checks;
import java.util.List;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/**
 * The rejections of an advance that admitted none of its automatic edges, one per edge it tried and in the order it
 * tried them, obtained from {@link IgnoredOutcomeAssert#rejections()}. Each one is read with the view of an ineligible
 * answer, {@link EligibilityAssert}, so its kind, guard, reason, origin and identifier are asserted as the eligibility
 * check asserts them.
 *
 * @param <E> entity type of the machine
 * @param <S> state enum of the machine
 * @param <T> transition identifier enum of the machine
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public final class RejectionsAssert<E, S extends Enum<S>, T extends Enum<T>> {

    private final String context;
    private final List<Eligibility.Ineligible<E, S, T>> actual;

    public RejectionsAssert<E, S, T> hasSize(int expected) {
        Checks.equal(context, "size", expected, actual.size());
        return this;
    }

    /** The one rejection of the advance; any other number fails rather than picking the first. */
    public EligibilityAssert<E, S, T> single() {
        Checks.equal(context, "size", 1, actual.size());
        return atIndex(0);
    }

    public EligibilityAssert<E, S, T> atIndex(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("index must not be negative: " + index);
        }
        Checks.equal(context, "index " + index + " present", Boolean.TRUE, index < actual.size());
        return EligibilityAssert.assertThat(actual.get(index));
    }
}
