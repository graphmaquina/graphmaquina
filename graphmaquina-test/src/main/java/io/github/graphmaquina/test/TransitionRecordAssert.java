package io.github.graphmaquina.test;

import io.github.graphmaquina.api.EdgeKind;
import io.github.graphmaquina.api.TransitionRecord;
import io.github.graphmaquina.test.internal.Checks;
import java.util.Objects;
import java.util.Optional;

/**
 * Chainable, typed assertion over a single {@link TransitionRecord}, obtained from
 * {@link TypedRecordsAssert#single()} or {@link TypedRecordsAssert#atIndex(int)}. A record describes one hop
 * of the transaction: where it went, which edge kind carried it, which action name ran if any, how deep in a
 * cascade it happened, and whether it was the idempotent no-op a convergent cascade produces.
 *
 * @param <E> entity type of the machine
 * @param <S> state enum of the machine
 */
public final class TransitionRecordAssert<E, S extends Enum<S>> {

    private final String context;
    private final TransitionRecord<E, S> actual;

    TransitionRecordAssert(String context, TransitionRecord<E, S> actual) {
        this.context = context;
        this.actual = actual;
    }

    public TransitionRecordAssert<E, S> from(S expected) {
        Objects.requireNonNull(expected, "expected");
        Checks.equal(context, "from", expected, actual.from());
        return this;
    }

    public TransitionRecordAssert<E, S> to(S expected) {
        Objects.requireNonNull(expected, "expected");
        Checks.equal(context, "to", expected, actual.to());
        return this;
    }

    public TransitionRecordAssert<E, S> hasKind(EdgeKind expected) {
        Objects.requireNonNull(expected, "expected");
        Checks.equal(context, "kind", expected, actual.kind());
        return this;
    }

    /** The runtime name of the action that ran; a hop that ran none never satisfies it. */
    public TransitionRecordAssert<E, S> hasActionNamed(String expected) {
        Objects.requireNonNull(expected, "expected");
        Checks.equal(context, "action", Optional.of(expected), actual.action());
        return this;
    }

    public TransitionRecordAssert<E, S> hasNoAction() {
        Optional<String> none = Optional.empty();
        Checks.equal(context, "action", none, actual.action());
        return this;
    }

    /** 0 at the root transition and n at the n-th level of cascade, exactly as the engine recorded it. */
    public TransitionRecordAssert<E, S> hasDepth(int expected) {
        Checks.equal(context, "depth", expected, actual.depth());
        return this;
    }

    /** Convergence is the engine's own flag for the idempotent no-op of a cascade, not a derived reading. */
    public TransitionRecordAssert<E, S> isConvergent() {
        Checks.equal(context, "convergent", Boolean.TRUE, actual.convergent());
        return this;
    }

    public TransitionRecordAssert<E, S> isNotConvergent() {
        Checks.equal(context, "convergent", Boolean.FALSE, actual.convergent());
        return this;
    }
}
