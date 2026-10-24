package io.github.graphmaquina.test;

import io.github.graphmaquina.api.TransitionKind;
import io.github.graphmaquina.api.TransitionRecord;
import io.github.graphmaquina.test.internal.Checks;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Chainable, typed assertion over a single {@link TransitionRecord}, obtained from
 * {@link TypedRecordsAssert#single()} or {@link TypedRecordsAssert#atIndex(int)}. A record describes one hop
 * of the transaction: which edge ran, where it went, whether it ran an action and which one, and how deep in
 * a cascade it happened. Identity plus origin is what tells two hops apart, so two actions with the same name
 * and the same endpoints remain distinguishable.
 *
 * @param <E> entity type of the machine
 * @param <S> state enum of the machine
 * @param <T> transition identifier enum of the machine
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public final class TransitionRecordAssert<E, S extends Enum<S>, T extends Enum<T>> {

    private final String context;
    private final TransitionRecord<E, S, T> actual;

    public TransitionRecordAssert<E, S, T> from(@NonNull S expected) {
        Checks.equal(context, "from", expected, actual.from());
        return this;
    }

    /** The identifier of the edge that ran, which is what selected it. */
    public TransitionRecordAssert<E, S, T> hasTransition(@NonNull T expected) {
        Checks.equal(context, "transition", expected, actual.transition());
        return this;
    }

    public TransitionRecordAssert<E, S, T> to(@NonNull S expected) {
        Checks.equal(context, "to", expected, actual.to());
        return this;
    }

    public TransitionRecordAssert<E, S, T> hasKind(@NonNull TransitionKind expected) {
        Checks.equal(context, "kind", expected, actual.kind());
        return this;
    }

    /** The runtime name of the action that ran; a hop that ran none never satisfies it. */
    public TransitionRecordAssert<E, S, T> hasActionNamed(@NonNull String expected) {
        Checks.equal(context, "action", Optional.of(expected), actual.action());
        return this;
    }

    public TransitionRecordAssert<E, S, T> hasNoAction() {
        Optional<String> none = Optional.empty();
        Checks.equal(context, "action", none, actual.action());
        return this;
    }

    /**
     * 0 at the root transition and n at the n-th level of cascade, exactly as the engine recorded it. The exit of a
     * sub-machine runs one level below the step that ended the child, unless the child stood in a terminal state while
     * its parent was still running the step that entered the bound state: that exit runs once that step is over, one
     * level below it.
     */
    public TransitionRecordAssert<E, S, T> hasDepth(int expected) {
        Checks.equal(context, "depth", expected, actual.depth());
        return this;
    }
}
