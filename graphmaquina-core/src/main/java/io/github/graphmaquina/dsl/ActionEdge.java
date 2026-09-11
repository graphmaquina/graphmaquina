package io.github.graphmaquina.dsl;

import io.github.graphmaquina.api.AsyncTransitionGuard;
import io.github.graphmaquina.api.CommitContext;
import io.github.graphmaquina.api.TransitionGuard;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Stage of an ACTION cell: guards, failure routing and the exception types that trigger it. */
public interface ActionEdge<E, S extends Enum<S>> extends RowBuilder<E, S> {

    ActionEdge<E, S> guard(TransitionGuard<E, S> guard);

    ActionEdge<E, S> guard(String name, TransitionGuard<E, S> guard);

    ActionEdge<E, S> asyncGuard(AsyncTransitionGuard<E, S> guard);

    ActionEdge<E, S> asyncGuard(String name, AsyncTransitionGuard<E, S> guard);

    ActionEdge<E, S> onFailure(S target);

    /** One per call, chainable. */
    ActionEdge<E, S> failsOn(Class<? extends Throwable> type);

    /** Post-commit hook of this cell. At most one per cell. */
    ActionEdge<E, S> afterCommit(String name, Consumer<? super E> hook);

    /** Post-commit hook with the committed cell's metadata and graph-derived terminality. Counts as the cell's single hook. */
    ActionEdge<E, S> afterCommitWith(String name, BiConsumer<? super E, ? super CommitContext<S>> hook);
}
