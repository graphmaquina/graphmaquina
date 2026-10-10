package io.github.graphmaquina.test;

import io.github.graphmaquina.api.TransitionKind;
import io.github.graphmaquina.api.TransitionRecord;
import io.github.graphmaquina.test.internal.Checks;
import java.util.Objects;
import java.util.Optional;

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
public final class TransitionRecordAssert<E, S extends Enum<S>, T extends Enum<T>> {

    private final String context;
    private final TransitionRecord<E, S, T> actual;

    TransitionRecordAssert(String context, TransitionRecord<E, S, T> actual) {
        this.context = context;
        this.actual = actual;
    }

    public TransitionRecordAssert<E, S, T> from(S expected) {
        Objects.requireNonNull(expected, "expected");
        Checks.equal(context, "from", expected, actual.from());
        return this;
    }

    /** The identifier of the edge that ran, which is what selected it. */
    public TransitionRecordAssert<E, S, T> hasTransition(T expected) {
        Objects.requireNonNull(expected, "expected");
        Checks.equal(context, "transition", expected, actual.transition());
        return this;
    }

    public TransitionRecordAssert<E, S, T> to(S expected) {
        Objects.requireNonNull(expected, "expected");
        Checks.equal(context, "to", expected, actual.to());
        return this;
    }

    public TransitionRecordAssert<E, S, T> hasKind(TransitionKind expected) {
        Objects.requireNonNull(expected, "expected");
        Checks.equal(context, "kind", expected, actual.kind());
        return this;
    }

    /** The runtime name of the action that ran; a hop that ran none never satisfies it. */
    public TransitionRecordAssert<E, S, T> hasActionNamed(String expected) {
        Objects.requireNonNull(expected, "expected");
        Checks.equal(context, "action", Optional.of(expected), actual.action());
        return this;
    }

    public TransitionRecordAssert<E, S, T> hasNoAction() {
        Optional<String> none = Optional.empty();
        Checks.equal(context, "action", none, actual.action());
        return this;
    }

    /**
     * 0 at the root transition and n at the n-th level of cascade, where the exit of a sub-machine is one level
     * deeper than the step that ended it, exactly as the engine recorded it.
     */
    public TransitionRecordAssert<E, S, T> hasDepth(int expected) {
        Checks.equal(context, "depth", expected, actual.depth());
        return this;
    }
}
