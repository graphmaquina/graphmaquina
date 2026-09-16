package io.github.graphmaquina.dsl;

import io.github.graphmaquina.api.AsyncTransitionAction;
import io.github.graphmaquina.api.AsyncTransitionGuard;
import io.github.graphmaquina.api.CommitContext;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.api.TransitionGuard;
import java.time.Duration;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Stage of one edge. Every combination is accepted here and validated when the edge is closed: an edge with no
 * action is a plain move, and {@code onFailure}, {@code failsOn} and {@code timeout} require one.
 */
public interface TransitionBuilder<E, S extends Enum<S>, T extends Enum<T>> {

    TransitionBuilder<E, S, T> action(TransitionAction<E, S, T> action);

    TransitionBuilder<E, S, T> action(String name, TransitionAction<E, S, T> action);

    TransitionBuilder<E, S, T> asyncAction(AsyncTransitionAction<E, S, T> action);

    TransitionBuilder<E, S, T> asyncAction(String name, AsyncTransitionAction<E, S, T> action);

    TransitionBuilder<E, S, T> guard(TransitionGuard<E, S, T> guard);

    TransitionBuilder<E, S, T> guard(String name, TransitionGuard<E, S, T> guard);

    TransitionBuilder<E, S, T> asyncGuard(AsyncTransitionGuard<E, S, T> guard);

    TransitionBuilder<E, S, T> asyncGuard(String name, AsyncTransitionGuard<E, S, T> guard);

    /** The identifier of the edge that recovers from a domain failure, resolved from this edge's origin. */
    TransitionBuilder<E, S, T> onFailure(T transition);

    /** One per call, chainable. */
    TransitionBuilder<E, S, T> failsOn(Class<? extends Throwable> type);

    TransitionBuilder<E, S, T> timeout(Duration timeout);

    /** Post-commit hook of this edge. At most one per edge. */
    TransitionBuilder<E, S, T> afterCommit(String name, Consumer<? super E> hook);

    /** Post-commit hook with the committed edge's metadata and graph-derived terminality. Counts as the edge's single hook. */
    TransitionBuilder<E, S, T> afterCommitWith(String name, BiConsumer<? super E, ? super CommitContext<S, T>> hook);

    /** Closes the edge and returns to the graph. */
    GraphBuilder<E, S, T> endTransition();
}
