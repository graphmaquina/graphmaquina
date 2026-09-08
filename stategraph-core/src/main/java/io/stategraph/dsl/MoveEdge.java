package io.stategraph.dsl;

import io.stategraph.api.AsyncTransitionGuard;
import io.stategraph.api.TransitionGuard;
import java.util.function.Consumer;

/** Stage of a MOVE cell: only guards apply. */
public interface MoveEdge<E, S extends Enum<S>> extends RowBuilder<E, S> {

    MoveEdge<E, S> guard(TransitionGuard<E, S> guard);

    MoveEdge<E, S> guard(String name, TransitionGuard<E, S> guard);

    MoveEdge<E, S> asyncGuard(AsyncTransitionGuard<E, S> guard);

    MoveEdge<E, S> asyncGuard(String name, AsyncTransitionGuard<E, S> guard);

    /** Post-commit hook of this cell. At most one per cell. */
    MoveEdge<E, S> afterCommit(String name, Consumer<? super E> hook);
}
