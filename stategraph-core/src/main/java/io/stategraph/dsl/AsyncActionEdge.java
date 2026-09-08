package io.stategraph.dsl;

import io.stategraph.api.AsyncTransitionGuard;
import io.stategraph.api.TransitionGuard;
import java.time.Duration;
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
}
