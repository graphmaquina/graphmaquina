package io.github.graphmaquina.dsl;

import io.github.graphmaquina.api.AsyncTransitionGuard;
import io.github.graphmaquina.api.CommitContext;
import io.github.graphmaquina.api.TransitionGuard;
import java.time.Duration;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Stage of an asynchronous ACTION cell: everything {@link ActionEdge} offers, plus a timeout. */
public interface AsyncActionEdge<E, S extends Enum<S>> extends ActionEdge<E, S> {

    AsyncActionEdge<E, S> timeout(Duration timeout);

    @Override AsyncActionEdge<E, S> guard(TransitionGuard<E, S> guard);

    @Override AsyncActionEdge<E, S> guard(String name, TransitionGuard<E, S> guard);

    @Override AsyncActionEdge<E, S> asyncGuard(AsyncTransitionGuard<E, S> guard);

    @Override AsyncActionEdge<E, S> asyncGuard(String name, AsyncTransitionGuard<E, S> guard);

    @Override AsyncActionEdge<E, S> onFailure(S target);

    @Override AsyncActionEdge<E, S> failsOn(Class<? extends Throwable> type);

    @Override AsyncActionEdge<E, S> afterCommit(String name, Consumer<? super E> hook);

    @Override AsyncActionEdge<E, S> afterCommitWith(String name, BiConsumer<? super E, ? super CommitContext<S>> hook);
}
