package io.github.graphmaquina.test;

import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.SubmachineStart;
import io.github.graphmaquina.test.internal.Checks;
import java.util.Objects;

/**
 * Chainable, typed assertion over a single {@link SubmachineStart}, obtained from {@link TypedStartsAssert#single()}
 * or {@link TypedStartsAssert#atIndex(int)}. A start puts a child in the initial state of its graph because its parent
 * entered the bound state; it is no hop, so it carries no identifier and runs no hook. The child's states are typed by
 * the key that selected the start, and the parent's by the key {@link #byParent(MachineKey, Object, Enum)} names.
 *
 * @param <P> entity type of the child machine
 * @param <Q> state enum of the child machine
 * @param <U> transition identifier enum of the child machine
 */
public final class StartAssert<P, Q extends Enum<Q>, U extends Enum<U>> {

    private final String context;
    private final SubmachineStart<?, ?, ?> actual;

    StartAssert(String context, SubmachineStart<?, ?, ?> actual) {
        this.context = context;
        this.actual = actual;
    }

    /** The state the start put the child in, which is the initial state of the child's graph. */
    public StartAssert<P, Q, U> startedAt(Q expected) {
        Objects.requireNonNull(expected, "expected");
        Checks.equal(context, "initial", expected, actual.initial());
        return this;
    }

    /** The state of the child before the start: its pending state in the call, or else what its getter returned. */
    public StartAssert<P, Q, U> hadPrevious(Q expected) {
        Objects.requireNonNull(expected, "expected");
        Checks.equal(context, "previous", expected, actual.previous());
        return this;
    }

    /**
     * The parent whose step entered the bound state: its machine by key, the entity by reference, and the state that
     * holds the binding.
     */
    public <E, S extends Enum<S>, T extends Enum<T>> StartAssert<P, Q, U> byParent(MachineKey<E, S, T> key, E parent,
            S node) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(node, "node");
        Checks.equal(context, "parent key", key, actual.parentKey());
        Checks.same(context, "parent", parent, actual.parent());
        Checks.equal(context, "node", node, actual.node());
        return this;
    }

    /**
     * 0 when the root's step entered the bound state and n when a step n levels deep did; a start that another start
     * caused has that start's depth.
     */
    public StartAssert<P, Q, U> atDepth(int expected) {
        Checks.equal(context, "depth", expected, actual.depth());
        return this;
    }
}
