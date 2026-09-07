package io.stategraph.dsl;

import io.stategraph.api.AsyncTransitionGuard;
import io.stategraph.api.TransitionGuard;

/** Stage of an ACTION cell: guards, failure routing and the exception types that trigger it. */
public interface ActionEdge<E, S extends Enum<S>> extends RowBuilder<E, S> {

    ActionEdge<E, S> guard(TransitionGuard<E, S> guard);

    ActionEdge<E, S> guard(String name, TransitionGuard<E, S> guard);

    ActionEdge<E, S> asyncGuard(AsyncTransitionGuard<E, S> guard);

    ActionEdge<E, S> asyncGuard(String name, AsyncTransitionGuard<E, S> guard);

    ActionEdge<E, S> onFailure(S target);

    /** One per call, chainable. */
    ActionEdge<E, S> failsOn(Class<? extends Throwable> type);
}
