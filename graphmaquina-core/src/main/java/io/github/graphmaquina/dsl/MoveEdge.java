package io.github.graphmaquina.dsl;

import io.github.graphmaquina.api.AsyncTransitionGuard;
import io.github.graphmaquina.api.CommitContext;
import io.github.graphmaquina.api.TransitionGuard;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Stage of a MOVE cell: only guards apply. */
public interface MoveEdge<E, S extends Enum<S>> extends RowBuilder<E, S> {

    MoveEdge<E, S> guard(TransitionGuard<E, S> guard);

    MoveEdge<E, S> guard(String name, TransitionGuard<E, S> guard);

    MoveEdge<E, S> asyncGuard(AsyncTransitionGuard<E, S> guard);

    MoveEdge<E, S> asyncGuard(String name, AsyncTransitionGuard<E, S> guard);

    /** Post-commit hook of this cell. At most one per cell. */
    MoveEdge<E, S> afterCommit(String name, Consumer<? super E> hook);

    /** Post-commit hook with the committed cell's metadata and graph-derived terminality. Counts as the cell's single hook. */
    MoveEdge<E, S> afterCommitWith(String name, BiConsumer<? super E, ? super CommitContext<S>> hook);
}
