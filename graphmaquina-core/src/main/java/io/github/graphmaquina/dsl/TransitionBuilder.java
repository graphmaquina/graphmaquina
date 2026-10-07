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
 * Stage of one edge. Every combination of actions, guards, {@code onFailure}, {@code failsOn} and {@code timeout} is
 * accepted here and validated when the edge is closed: an edge with no action is a plain move, {@code onFailure} and
 * {@code failsOn} require an action, and {@code timeout} an asynchronous one. An edge runs at most one action and
 * declares at most one {@code onFailure} and one {@code timeout}: a second one is reported as SG507 rather than
 * swapped in. Once the edge is closed, or the graph built, the stage refuses every call with
 * {@link IllegalStateException}.
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

    /**
     * The identifier of the edge that recovers from a domain failure: the action returns a failure, or throws a
     * throwable {@code failsOn} lists. It is resolved from this edge's origin, and the recovery edge runs without its
     * guards and without a failure route of its own.
     */
    TransitionBuilder<E, S, T> onFailure(T transition);

    /**
     * One per call, chainable. SG302 accepts only throwable types foreign to the library. It refuses {@code Throwable},
     * {@code Exception} and {@code RuntimeException} as too broad, an {@code Error} or a
     * {@code CancellationException} as never a domain failure, and {@code CompletionException} or
     * {@code ExecutionException} themselves because they are unwrapped before matching: declare the type of their
     * cause instead.
     */
    TransitionBuilder<E, S, T> failsOn(Class<? extends Throwable> type);

    TransitionBuilder<E, S, T> timeout(Duration timeout);

    /**
     * Post-commit hook of this edge. At most one per edge: a second one, through either method, throws
     * {@link IllegalStateException} at the call.
     */
    TransitionBuilder<E, S, T> afterCommit(String name, Consumer<? super E> hook);

    /**
     * Post-commit hook with the committed edge's metadata and graph-derived terminality. Counts as the edge's single
     * hook.
     */
    TransitionBuilder<E, S, T> afterCommitWith(String name, BiConsumer<? super E, ? super CommitContext<S, T>> hook);

    /** Closes the edge and returns to the graph. */
    GraphBuilder<E, S, T> endTransition();
}
