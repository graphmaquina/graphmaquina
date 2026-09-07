package io.stategraph.dsl;

import io.stategraph.api.AsyncTransitionGuard;
import io.stategraph.api.TransitionGuard;

/** Stage of a STAY cell: only guards apply. */
public interface StayEdge<E, S extends Enum<S>> extends RowBuilder<E, S> {

    StayEdge<E, S> guard(TransitionGuard<E, S> guard);

    StayEdge<E, S> guard(String name, TransitionGuard<E, S> guard);

    StayEdge<E, S> asyncGuard(AsyncTransitionGuard<E, S> guard);

    StayEdge<E, S> asyncGuard(String name, AsyncTransitionGuard<E, S> guard);
}
